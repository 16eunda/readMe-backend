# 📖 추천 로직 전체 흐름 설명

## 🔄 전체 흐름 한눈에 보기

```
사용자가 추천 요청 (GET /recommendations)
        │
        ▼
┌─────────────────────────┐
│ 1. 사용자 구분           │  RecController → RecService.getRecommendations()
│   로그인? → userId 사용  │
│   게스트? → deviceId 사용│
└─────────┬───────────────┘
          │
          ▼
┌─────────────────────────┐
│ 2. 미분석 파일 분석       │  analyzeUnanalyzedFiles()
│   (PENDING/FAILED만)     │
│   최대 10개까지          │
└─────────┬───────────────┘
          │
          ▼
┌─────────────────────────┐
│ 3. 추천 파일 선정        │  getRecommendationsForUser() 또는 ForDevice()
│   ① 장르 기반 (최대 3개) │
│   ② 별점 기반 (빈자리)   │
│   ③ 읽다만 파일 (빈자리) │
│   → 총 최대 5개         │
└─────────────────────────┘
```

---

## 📁 관련 파일

| 파일 | 역할 |
|------|------|
| `RecController.java` | API 엔드포인트 (`GET /recommendations`) |
| `RecService.java` | **추천 로직 전체** |
| `RecRepository.java` | 추천용 DB 쿼리 (장르 기반, 읽다만 파일 등) |
| `FileRepository.java` | 미분석 파일 조회, 별점 높은 파일, 최근 읽은 파일 등 |
| `GeminiService.java` | AI 분석 (Gemini API 호출) |
| `FileService.java` | 파일 저장 (분석은 안 함, PENDING 상태로만 저장) |

---

## 1단계: 사용자 구분

**파일: `RecService.java` → `getRecommendations()`**

```
프론트에서 요청:
  GET /recommendations
  Header: X-Device-Id: abc123
  Header: Authorization: Bearer xxx (로그인 시)
```

```java
public List<FileEntity> getRecommendations(Authentication authentication, String deviceId) {
    Long userId = extractUserId(authentication);
    
    if (userId != null) {
        // ✅ 로그인 사용자 → userId로 모든 처리
        analyzeUnanalyzedFiles(userId, null);     // deviceId 안 씀
        return getRecommendationsForUser(userId);
    } else if (deviceId != null) {
        // ✅ 게스트 → deviceId로 모든 처리
        analyzeUnanalyzedFiles(null, deviceId);   // userId 안 씀
        return getRecommendationsForDevice(deviceId);
    }
    
    return []; // 둘 다 없으면 빈 리스트
}
```

### ❓ Q: 로그인 사용자면 deviceId도 있는데, 왜 null로 보내?

**A: 로그인하면 파일이 `user.id`로 연결되어 있기 때문!**

```
[파일 저장 시]
  게스트: deviceId = "abc123", user = null    ← deviceId로 찾음
  로그인: deviceId = "abc123", user = {id: 1} ← user.id로 찾음

[로그인 시 자동 연결 - AuthService]
  게스트 파일의 user를 로그인한 유저로 연결
  → 이후 user.id로만 찾으면 됨
```

즉 `analyzeUnanalyzedFiles(userId, null)`에서:
- **userId가 있으면** → `findUnanalyzedFilesByUserId(userId)` 호출 (deviceId 무시)
- **userId가 null이면** → `findUnanalyzedFilesByDeviceId(deviceId)` 호출

**한쪽만 사용하므로 다른 쪽은 null이어도 됩니다.**

---

## 2단계: 미분석 파일 분석 (Lazy 분석)

**파일: `RecService.java` → `analyzeUnanalyzedFiles()`**

### 왜 여기서 분석하나?

```
[이전 방식] 파일 저장할 때마다 AI 분석 → 💸 API 낭비
  파일 100개 저장 → AI 100번 호출 (추천 안 쓰는 사용자도!)

[현재 방식] 추천 요청할 때만 분석 → 💰 절약
  파일 100개 저장 → AI 0번 호출
  추천 요청 1번 → 미분석 파일만 분석 (최대 10개)
```

### 분석 대상은?

```sql
-- FileRepository의 쿼리
SELECT f FROM FileEntity f 
WHERE f.user.id = :userId 
AND (f.aiGenre IS NULL               -- 장르가 아직 없거나
     OR f.analysisStatus = 'PENDING'  -- 대기 상태이거나
     OR f.analysisStatus = 'FAILED')  -- 이전에 실패했거나
AND f.analysisStatus != 'SKIPPED'     -- preview 없어서 스킵된 건 제외
ORDER BY f.lastReadAt DESC NULLS LAST -- 최근 읽은 파일 우선
```

### analysisStatus 상태값

| 상태 | 의미 | 언제 설정? |
|------|------|-----------|
| `PENDING` | 분석 대기 | 파일 저장 시 (새 파일) |
| `DONE` | 분석 완료 | AI 분석 성공 또는 복사 |
| `FAILED` | 분석 실패 | AI API 호출 실패 |
| `SKIPPED` | 분석 불가 | preview 텍스트가 없음 |

### 분석 과정 (파일 하나당)

```
파일 "해리포터.epub" 분석 시도
        │
        ▼
① 같은 제목으로 이미 분석된 파일 있어? ──── YES → 복사 (API 0회) ✅
        │ NO
        ▼
② preview 텍스트가 있어? ──── NO → SKIPPED (분석 불가) ⚠️
        │ YES
        ▼
③ Gemini AI 호출 → 장르/키워드/분위기/요약/타겟 분석
        │
   성공 → DONE ✅    실패 → FAILED ❌ (다음 추천 때 재시도)
```

### AI 분석 결과 예시

```json
{
  "genre": "판타지",
  "keywords": "마법,학교,우정,모험,선과악",
  "mood": "신비로운,모험적",
  "summary": "마법 학교에 입학한 소년의 성장과 모험 이야기",
  "target": "판타지를 좋아하는 10대~20대 독자"
}
```

---

## 3단계: 추천 파일 선정

### 로그인 사용자 (`getRecommendationsForUser`)

**총 최대 5개를 3가지 방식으로 채움:**

```
추천 리스트 = []  (최대 5개)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 장르 기반 추천 (최대 3개)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  1) 최근 읽은 파일 10개 가져오기
     → findTop10ByUserId...OrderByLastReadAtDesc(userId)

  2) 그 중 가장 많이 읽은 장르 찾기
     예: [로맨스, 판타지, 로맨스, 로맨스] → "로맨스" (3회)
     ※ "미분류" 장르는 제외

  3) 같은 장르인데 아직 안 읽은 파일 추천
     → findByAiGenreAndLastReadAtIsNullAndUserId("로맨스", userId)
     → 최대 3개

  결과: 추천 리스트 = [로맨스A, 로맨스B, 로맨스C]  (3개)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 별점 기반 추천 (빈 자리 채움)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  1) 별점 4점 이상 + AI 분석 완료된 파일 가져오기
     → findHighRatedFilesByUserId(userId, 4)

  2) 그 파일들의 장르 수집 (①에서 이미 추천된 장르는 제외)
     예: 별점 5점 파일의 장르 = [스릴러, SF]
         ①에서 이미 추천된 장르 = 로맨스 → 제외 안 됨
         남은 장르 = [스릴러, SF]

  3) 각 장르에서 안 읽은 파일 1개씩 추천
     → 스릴러에서 1개, SF에서 1개

  결과: 추천 리스트 = [로맨스A, 로맨스B, 로맨스C, 스릴러X, SFY]  (5개 → 끝!)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ 읽다 만 파일 추천 (아직 빈 자리 있으면)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ※ ①②로 5개 못 채웠을 때만 실행

  1) progress가 10%~90%인 파일 찾기
     → findByProgressBetweenAndUserId(0.1, 0.9, userId)
  
  2) 이미 추천된 파일 제외하고 나머지 추가

  예: ①에서 2개, ②에서 1개 = 3개 → 읽다만 파일 2개 추가 → 총 5개
```

### 게스트 사용자 (`getRecommendationsForDevice`)

**①과 ③만 사용 (별점 데이터가 부족하므로 ② 생략)**

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 장르 기반 추천 (최대 3개) — 로그인과 동일
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 읽다 만 파일 추천 (빈 자리 채움)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 🗄️ DB 쿼리 정리

### RecRepository (추천 전용)

| 메서드 | 용도 | 조건 |
|--------|------|------|
| `findByAiGenreAndLastReadAtIsNullAndUserId` | 장르 기반 추천 | 같은 장르 + 안 읽은 파일 |
| `findByProgressBetweenAndUserId` | 읽다 만 파일 | progress 10%~90% |
| `findByAiGenreAndLastReadAtIsNullAndDeviceIdAndUserIsNull` | 게스트 장르 기반 | 같은 장르 + 안 읽은 파일 |
| `findByProgressBetweenAndDeviceIdAndUserIsNull` | 게스트 읽다만 | progress 10%~90% |

### FileRepository (추천에 사용되는 것)

| 메서드 | 용도 |
|--------|------|
| `findTop10ByUserId...OrderByLastReadAtDesc` | 최근 읽은 파일 10개 (장르 분석용) |
| `findHighRatedFilesByUserId` | 별점 4점 이상 파일 (별점 추천용) |
| `findUnanalyzedFilesByUserId` | 미분석 파일 조회 (lazy 분석용) |
| `findFirstByNormalizedTitle...` | 같은 제목의 분석된 파일 찾기 (복사용) |

---

## 🎯 예시 시나리오

### 시나리오 1: 로맨스 많이 읽는 사용자

```
최근 읽은 파일: [나의 아저씨(로맨스), 봄날(로맨스), 재벌집(스릴러)]
별점 5점 파일: [해리포터(판타지)]
읽다 만 파일: [1984(SF, 진행률 45%)]

추천 결과:
  ① 장르 기반: [로맨스X, 로맨스Y, 로맨스Z]  ← "로맨스" 2회로 최다
  ② 별점 기반: [판타지A]                     ← 해리포터가 5점이니까
  ③ 읽다 만:  [1984]                        ← 45%에서 멈춤
  
  → 총 5개: [로맨스X, 로맨스Y, 로맨스Z, 판타지A, 1984]
```

### 시나리오 2: 신규 사용자 (아무것도 안 읽음)

```
최근 읽은 파일: []
별점 파일: []
읽다 만 파일: []

추천 결과:
  ① 장르 기반: []    ← 데이터 없음
  ② 별점 기반: []    ← 데이터 없음
  ③ 읽다 만: []      ← 데이터 없음
  
  → 빈 리스트 []  (프론트에서 "파일을 읽어보세요!" 메시지 표시)
```

### 시나리오 3: 파일 5개 저장했지만 한 번도 안 읽음

```
최근 읽은 파일: []   ← lastReadAt이 전부 null
→ 추천 불가 (장르 분석할 데이터 없음)

BUT! 미분석 파일 5개는 분석됨 (다음 번에 읽으면 추천에 활용)
```

---

## 💰 AI 호출 절약 효과

### 이전 방식
```
파일 저장 → 무조건 AI 호출
  파일 50개 저장 → AI 50번 호출 💸
  추천 안 쓰는 사용자도 호출됨 💸
  같은 책 다른 기기에서 저장 → 또 호출 💸
```

### 현재 방식
```
파일 저장 → AI 호출 안 함 (PENDING만 설정)
  파일 50개 저장 → AI 0번 호출 ✅

추천 요청 → 그때 분석
  미분석 10개 → 같은 제목 3개 복사 + AI 7번 호출
  2번째 추천 요청 → 이미 분석됨 → AI 0번 호출 ✅
```

---

## 📝 파일 저장부터 추천까지 전체 타임라인

```
[Day 1] 사용자가 파일 5개 업로드
  → FileService.saveFile()
  → 중복 제목 체크 → 없으면 analysisStatus = "PENDING"
  → AI 호출 없음 ✅

[Day 1] 사용자가 "해리포터" 읽기 시작
  → FileService.updateProgress()
  → progress = 0.3, lastReadAt = now()

[Day 2] 사용자가 "반지의 제왕" 읽기 시작
  → progress = 0.5, lastReadAt = now()

[Day 3] 사용자가 추천 요청
  → RecService.getRecommendations()
  
  [2단계] 미분석 파일 5개 발견
    → "해리포터" - Gemini AI 호출 → 장르: 판타지 ✅
    → "반지의 제왕" - Gemini AI 호출 → 장르: 판타지 ✅
    → "노르웨이의 숲" - Gemini AI 호출 → 장르: 로맨스 ✅
    → "사피엔스" - preview 없음 → SKIPPED ⚠️
    → "1984" - Gemini AI 호출 → 장르: SF ✅

  [3단계] 추천 선정
    최근 읽은: [반지의 제왕(판타지), 해리포터(판타지)]
    선호 장르: 판타지 (2회)
    
    ① 장르 기반: 판타지 중 안 읽은 파일 → 없음 (전부 읽음)
    ② 별점 기반: 별점 없음 → 스킵
    ③ 읽다 만: [해리포터(30%), 반지의 제왕(50%)]
    
    → 추천: [해리포터, 반지의 제왕]

[Day 4] 사용자가 또 추천 요청
  → 미분석 파일 0개 → AI 호출 없음 ✅
  → 바로 추천 선정으로 이동 (빠름!)
```
