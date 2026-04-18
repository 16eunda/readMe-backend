# 🔧 FileEntity User 관계 수정으로 인한 쿼리 오류 해결

## 🐛 발생한 오류

```
Could not resolve attribute 'userId' of 'com.ReadMe.demo.domain.FileEntity'
Query 'UPDATE FileEntity f SET f.userId = :userId WHERE f.deviceId = :deviceId AND f.userId IS NULL' validation failed
```

## 🔍 원인

`FileEntity`에서 `userId`가 **Long 타입 필드**가 아니라 **UserEntity와의 @ManyToOne 관계**로 변경되었습니다:

```java
// ❌ 이전 (Long 타입)
private Long userId;

// ✅ 현재 (관계)
@ManyToOne
@JoinColumn(name = "user_id")
private UserEntity user;
```

## ✅ 해결 방법

### 1. **FileRepository 수정**

#### ❌ 잘못된 쿼리 (userId 필드에 직접 접근)
```java
@Query("UPDATE FileEntity f SET f.userId = :userId ...")
```

#### ✅ 수정된 쿼리 (user.id로 접근)
```java
@Query("UPDATE FileEntity f SET f.user.id = :userId WHERE f.deviceId = :deviceId AND f.user IS NULL")
int linkDeviceToUser(@Param("deviceId") String deviceId, @Param("userId") Long userId);
```

### 2. **JPA 메서드 네이밍 수정**

#### ❌ 잘못된 메서드명
```java
Page<FileEntity> findByPathAndDeviceIdAndUserIdIsNull(...)
long countByDeviceIdAndUserIdIsNull(...)
```

#### ✅ 수정된 메서드명
```java
Page<FileEntity> findByPathAndDeviceIdAndUserIsNull(...)
long countByDeviceIdAndUserIsNull(...)
```

**차이점**: `UserId` → `User` (관계 객체명 사용)

### 3. **@Query 어노테이션 추가**

복잡한 관계 접근은 JPQL로 명시:

```java
// userId별 전체 파일 수
@Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId")
long countByUserId(@Param("userId") Long userId);

// userId별 완독 파일 수
@Query("SELECT COUNT(f) FROM FileEntity f WHERE f.progress = :progress AND f.user.id = :userId")
long countByProgressAndUserId(@Param("progress") Double progress, @Param("userId") Long userId);

// userId별 최근 읽은 파일
@Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt DESC")
List<FileEntity> findTop50ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("userId") Long userId);
```

---

## 📝 수정된 파일 목록

### 1. **FileRepository.java**
- `findByPathAndDeviceIdAndUserIdIsNull` → `findByPathAndDeviceIdAndUserIsNull`
- `countByDeviceIdAndUserIdIsNull` → `countByDeviceIdAndUserIsNull`
- `countByProgressAndDeviceIdAndUserIdIsNull` → `countByProgressAndDeviceIdAndUserIsNull`
- `countByRatingAndDeviceIdAndUserIdIsNull` → `countByRatingAndDeviceIdAndUserIsNull`
- `linkDeviceToUser` 쿼리: `f.userId` → `f.user.id`
- userId 관련 메서드에 `@Query` 추가

### 2. **RecRepository.java**
- userId 필터링 메서드에 `@Query` 추가:
  - `findByAiGenreAndLastReadAtIsNullAndUserId`
  - `findByLastReadAtIsNullAndUserIdOrderByRatingDesc`
  - `findByProgressBetweenAndUserId`

### 3. **FileService.java**
- `findByPathAndDeviceIdAndUserIdIsNull` → `findByPathAndDeviceIdAndUserIsNull`

### 4. **FileStatsService.java**
- `countByDeviceIdAndUserIdIsNull` → `countByDeviceIdAndUserIsNull`
- `countByProgressAndDeviceIdAndUserIdIsNull` → `countByProgressAndDeviceIdAndUserIsNull`
- `countByRatingAndDeviceIdAndUserIdIsNull` → `countByRatingAndDeviceIdAndUserIsNull`

### 5. **AuthService.java**
- `countByDeviceIdAndUserIdIsNull` → `countByDeviceIdAndUserIsNull`

---

## 🎯 핵심 규칙

### JPA 메서드 네이밍 규칙

#### 단순 필드 (Long, String 등)
```java
// FileEntity의 deviceId (String 타입)
findByDeviceId(String deviceId)
```

#### 관계 필드 (@ManyToOne, @OneToMany 등)
```java
// FileEntity의 user (UserEntity 관계)
findByUser(UserEntity user)           // 객체로 찾기
findByUser_Id(Long userId)            // ID로 찾기 (언더스코어)
findByUserIsNull()                    // null 체크

// ❌ 잘못된 예
findByUserId(...)  // userId라는 필드가 없으면 오류
```

#### NULL 체크
```java
// 단순 필드
findByUserIdIsNull()  // userId 필드가 null

// 관계 필드
findByUserIsNull()    // user 관계가 null
```

---

## ✅ 테스트 체크리스트

- [x] 애플리케이션 시작 오류 해결
- [ ] 게스트 모드: deviceId로 파일 조회
- [ ] 로그인: userId로 파일 조회
- [ ] 로그인 시 게스트 파일/폴더 연결 (`linkDeviceToUser`)
- [ ] 통계 API 정상 작동
- [ ] 추천 API 정상 작동
- [ ] 랭킹 API 정상 작동

---

## 🚀 실행 방법

```bash
cd /Users/16eunho/projects/readMe-backend
./gradlew bootRun
```

정상 실행 확인 후:
1. H2 콘솔 접속: http://localhost:8080/h2-console
2. API 테스트 시작

---

## 💡 참고사항

### FolderRepository도 동일하게 수정 필요

`FolderEntity`도 `UserEntity`와 관계가 있다면 동일한 패턴으로 수정:

```java
// FolderRepository
@Query("UPDATE FolderEntity f SET f.user.id = :userId WHERE f.deviceId = :deviceId AND f.user IS NULL")
int linkDeviceToUser(@Param("deviceId") String deviceId, @Param("userId") Long userId);
```

### 기타 엔티티

- `FileReadLog`는 `FileEntity`를 통해 필터링되므로 직접 수정 불필요
- 향후 새로운 엔티티 추가 시 관계 필드 접근 방식 주의!
