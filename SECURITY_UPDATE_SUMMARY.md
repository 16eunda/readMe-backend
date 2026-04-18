# 보안 필터링 업데이트 요약

## 🔒 적용된 보안 필터링

모든 데이터 조회 API에서 **userId** 또는 **deviceId**로 필터링하여 각 사용자/디바이스의 데이터만 조회하도록 수정했습니다.

---

## ✅ 수정된 컴포넌트

### 1. **FolderEntity** (도메인)
- ✨ `userId` (UserEntity 관계) 추가
- ✨ `deviceId` 필드 추가
- 파일과 동일한 소유권 관리 적용

### 2. **FolderRepository**
```java
// userId로 폴더 조회 (로그인 사용자)
List<FolderEntity> findByUserId(Long userId);

// deviceId로 폴더 조회 (게스트)
List<FolderEntity> findByDeviceIdAndUserIsNull(String deviceId);

// 로그인 시 연결
int linkDeviceToUser(String deviceId, Long userId);
```

### 3. **FolderService**
- `getAll(userId, deviceId)` - 필터링된 폴더 목록 조회
- userId 우선, deviceId 대체, 둘 다 없으면 빈 리스트

### 4. **FolderController**
- `GET /folders?userId=&deviceId=` - 파라미터 추가

### 5. **FileReadLogRepository**
```java
// userId별 랭킹
List<FileRankingDto> findRankingSinceByUserId(LocalDateTime from, Long userId, Pageable pageable);

// deviceId별 랭킹
List<FileRankingDto> findRankingSinceByDeviceId(LocalDateTime from, String deviceId, Pageable pageable);
```

### 6. **RankingService & RankingController**
- `GET /ranking/month?userId=&deviceId=`
- `GET /ranking/year?userId=&deviceId=`
- 자신의 파일만 랭킹에 표시

### 7. **FileRepository**
```java
// 통계용 필터링 메서드
long countByUserId(Long userId);
long countByDeviceIdAndUserIsNull(String deviceId);
long countByProgressAndUserId(Double progress, Long userId);
long countByProgressAndDeviceIdAndUserIsNull(Double progress, String deviceId);

// 추천용 필터링 메서드
List<FileEntity> findTop50ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(Long userId);
List<FileEntity> findTop50ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(String deviceId);
```

### 8. **FileStatsService & FileStatsController**
- `GET /files/stats?userId=&deviceId=`
- totalCount, completedCount, fiveStarCount - 사용자별로 집계

### 9. **RecRepository**
```java
// 추천용 필터링 메서드 (userId / deviceId)
List<FileEntity> findByAiGenreAndLastReadAtIsNullAndUserId(String genre, Long userId);
List<FileEntity> findByProgressBetweenAndUserId(double min, double max, Long userId);
List<FileEntity> findByAiGenreAndLastReadAtIsNullAndDeviceIdAndUserIsNull(String genre, String deviceId);
List<FileEntity> findByProgressBetweenAndDeviceIdAndUserIsNull(double min, double max, String deviceId);
```

### 10. **RecService & RecController**
- `GET /recommendations?userId=&deviceId=`
- 사용자별 맞춤 추천 (장르 기반 + 읽다 만 파일)

### 11. **AuthService**
- 로그인 시 **파일 + 폴더** 모두 deviceId → userId 연결
- 게스트 모드 데이터를 로그인 계정으로 통합

---

## 🐛 수정된 버그

### H2 DATE 함수 오류
**문제**: `Function "DATE" not found` 에러 발생

**원인**: H2 데이터베이스는 MySQL/PostgreSQL의 `DATE()` 함수를 지원하지 않음

**해결**: 날짜 범위 비교로 변경
```java
// 변경 전 (네이티브 쿼리)
AND DATE(r.read_at) = CURRENT_DATE

// 변경 후 (JPQL + 날짜 범위)
AND r.readAt >= :startOfDay
AND r.readAt < :startOfNextDay
```

---

## 📍 로그 출력 위치
```
📝 새로운 로그 생성 중...
```
이 로그는 `FileService.java:241` (약)에서 출력됩니다.
- 조건: `recordReadLog=true` && 오늘 처음 읽는 파일

---

## 🔐 보안 강화 효과

### 이전 문제
```
❌ 모든 사용자의 폴더가 보임
❌ 다른 사용자의 파일이 랭킹/추천에 포함됨
❌ 통계에 다른 사용자 데이터 포함
```

### 현재 상태
```
✅ userId 또는 deviceId로 완전 격리
✅ 자신의 데이터만 조회/수정 가능
✅ 로그인 시 게스트 데이터 자동 연결
```

---

## 🎯 프론트엔드 수정 필요사항

모든 API 호출 시 `userId` 또는 `deviceId` 쿼리 파라미터 추가:

```typescript
// 예시
const response = await fetch(
  `${BASE_URL}/folders?userId=${userId}&deviceId=${deviceId}`
);

const response = await fetch(
  `${BASE_URL}/ranking/month?userId=${userId}&deviceId=${deviceId}`
);

const response = await fetch(
  `${BASE_URL}/files/stats?userId=${userId}&deviceId=${deviceId}`
);

const response = await fetch(
  `${BASE_URL}/recommendations?userId=${userId}&deviceId=${deviceId}`
);
```

---

## 📝 테스트 체크리스트

- [ ] 게스트 모드: deviceId로 폴더/파일 조회
- [ ] 로그인: userId로 폴더/파일 조회
- [ ] 로그인 시 게스트 데이터 연결 확인
- [ ] 다른 사용자의 데이터가 보이지 않는지 확인
- [ ] 랭킹/추천/통계가 자신의 데이터만 반영하는지 확인
- [ ] 파일 읽기 로그가 정상적으로 저장되는지 확인 (H2 오류 해결)

---

## 🚀 배포 시 주의사항

1. **데이터베이스 마이그레이션**
   - `folder_entity` 테이블에 `user_id`, `device_id` 컬럼 추가
   - 기존 데이터는 `deviceId`나 `userId` null 상태

2. **기존 데이터 처리**
   - 기존 폴더는 소유자가 없으므로 처음 조회 시 빈 리스트 반환
   - 필요시 데이터 마이그레이션 스크립트 작성

3. **API 호환성**
   - 모든 API가 `userId`, `deviceId` 파라미터를 `required=false`로 받음
   - 둘 다 없으면 빈 결과 반환 (보안 강화)
