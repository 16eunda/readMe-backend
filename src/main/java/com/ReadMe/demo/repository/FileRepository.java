package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.FileType;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.dto.FileGenreKeywordDto;
import com.ReadMe.demo.dto.HistoryFileDto;
import com.ReadMe.demo.dto.RecFileDto;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 소유 범위 규칙
 * - 로그인 사용자: user.id = 내 id
 * - 비회원: deviceId = 이 기기 AND user IS NULL
 *   로그인하면 이 기기의 비회원 파일이 계정으로 연결되고(user 가 채워짐), 로그인한 채 등록한 파일에도 deviceId 가 남는다.
 *   user IS NULL 없이 deviceId 로만 찾으면, 로그아웃한 비회원 화면에 방금 로그아웃한 계정의 파일이 보이고 수정·삭제까지 된다.
 */
public interface FileRepository extends JpaRepository<FileEntity, Long> {
    // 파일 경로로 조회
    Page<FileEntity> findByPath(String path, Pageable pageable);

    // 게스트용 - @Lob 제외하고 필요한 컬럼만 조회
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileDto(
            f.id, f.title, f.preview, f.date, f.rating, f.uri, f.path, f.review, f.progress, f.epubCfi, f.anchorRatio, f.readingPreview
        )
        FROM FileEntity f
        WHERE f.path = :path AND f.deviceId = :deviceId AND f.user IS NULL
    """)
    Page<FileDto> findByPathAndDeviceId(
            @Param("path") String path,
            @Param("deviceId") String deviceId,
            Pageable pageable
    );

    // 로그인용 - @Lob 제외하고 필요한 컬럼만 조회
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileDto(
            f.id, f.title, f.preview, f.date, f.rating, f.uri, f.path, f.review, f.progress, f.epubCfi, f.anchorRatio, f.readingPreview
        )
        FROM FileEntity f
        WHERE f.path = :path AND f.user.id = :userId
    """)
    Page<FileDto> findByPathAndUserId(
            @Param("path") String path,
            @Param("userId") Long userId,
            Pageable pageable
    );

    Optional<FileEntity> findByIdAndUserId(Long id, Long userId);

    Optional<FileEntity> findByIdAndDeviceIdAndUserIsNull(Long id, String deviceId);

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.user.id = :userId
        AND (f.date > :date OR (f.date = :date AND f.id > :id))
    """)
    long countBeforeDateDescByUserId(
            @Param("path") String path, @Param("userId") Long userId,
            @Param("date") java.time.Instant date, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.user.id = :userId
        AND (f.date < :date OR (f.date = :date AND f.id < :id))
    """)
    long countBeforeDateAscByUserId(
            @Param("path") String path, @Param("userId") Long userId,
            @Param("date") java.time.Instant date, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.user.id = :userId
        AND (f.rating > :rating OR (f.rating = :rating AND f.id > :id))
    """)
    long countBeforeRatingDescByUserId(
            @Param("path") String path, @Param("userId") Long userId,
            @Param("rating") int rating, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.user.id = :userId
        AND (f.rating < :rating OR (f.rating = :rating AND f.id < :id))
    """)
    long countBeforeRatingAscByUserId(
            @Param("path") String path, @Param("userId") Long userId,
            @Param("rating") int rating, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.deviceId = :deviceId AND f.user IS NULL
        AND (f.date > :date OR (f.date = :date AND f.id > :id))
    """)
    long countBeforeDateDescByDeviceId(
            @Param("path") String path, @Param("deviceId") String deviceId,
            @Param("date") java.time.Instant date, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.deviceId = :deviceId AND f.user IS NULL
        AND (f.date < :date OR (f.date = :date AND f.id < :id))
    """)
    long countBeforeDateAscByDeviceId(
            @Param("path") String path, @Param("deviceId") String deviceId,
            @Param("date") java.time.Instant date, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.deviceId = :deviceId AND f.user IS NULL
        AND (f.rating > :rating OR (f.rating = :rating AND f.id > :id))
    """)
    long countBeforeRatingDescByDeviceId(
            @Param("path") String path, @Param("deviceId") String deviceId,
            @Param("rating") int rating, @Param("id") Long id
    );

    @Query("""
        SELECT COUNT(f) FROM FileEntity f
        WHERE f.path = :path AND f.deviceId = :deviceId AND f.user IS NULL
        AND (f.rating < :rating OR (f.rating = :rating AND f.id < :id))
    """)
    long countBeforeRatingAscByDeviceId(
            @Param("path") String path, @Param("deviceId") String deviceId,
            @Param("rating") int rating, @Param("id") Long id
    );

    // 추가: userId와 id 리스트로 파일 삭제 (보안 필터링)
    void deleteByUserAndIdIn(UserEntity user, List<Long> ids);

    // deviceId와 id 리스트로 파일 삭제
    void deleteByDeviceIdAndUserIsNullAndIdIn(String deviceId, List<Long> ids);

    // 회원 탈퇴 시 계정의 파일 전체 삭제 (엔티티 단위로 지우므로 읽기 기록도 cascade 로 함께 삭제된다)
    void deleteByUser(UserEntity user);

    // 중복 확인은 요청자 화면에 보이는 목록과 같은 범위에서 한다.
    boolean existsByUser_IdAndTitleAndPath(Long userId, String title, String path);

    boolean existsByDeviceIdAndUserIsNullAndTitleAndPath(String deviceId, String title, String path);

    // 최근 읽은 파일 (히스토리)
    List<FileEntity> findTop50ByLastReadAtIsNotNullOrderByLastReadAtDesc();

    // 검색 메서드 추가
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileDto(
            f.id, f.title, f.preview, f.date, f.rating, f.uri, f.path, f.review, f.progress, f.epubCfi, f.anchorRatio, f.readingPreview
        )
        FROM FileEntity f
        WHERE f.user.id = :userId AND LOWER(f.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
    """)
    Page<FileDto> findByUserIdAndTitleContainingIgnoreCase(
            @Param("userId") Long userId, @Param("keyword") String keyword, Pageable pageable
    );

    @Query("""
        SELECT new com.ReadMe.demo.dto.FileDto(
            f.id, f.title, f.preview, f.date, f.rating, f.uri, f.path, f.review, f.progress, f.epubCfi, f.anchorRatio, f.readingPreview
        )
        FROM FileEntity f
        WHERE f.deviceId = :deviceId AND f.user IS NULL AND LOWER(f.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
    """)
    Page<FileDto> findByDeviceIdAndTitleContainingIgnoreCase(
            @Param("deviceId") String deviceId, @Param("keyword") String keyword, Pageable pageable
    );

    // ===== 통계용 메서드 (userId 필터링) =====

    // 전체 파일 수
    long count();

    // userId별 전체 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    // deviceId별 전체 파일 수 (= 로그인 시 연결 가능한 파일 수)
    long countByDeviceIdAndUserIsNull(String deviceId);

    // userId별 완독 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.completed = true AND f.user.id = :userId")
    long countCompletedFilesByUserId(@Param("userId") Long userId);

    // deviceId별 완독 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.completed = true AND f.deviceId = :deviceId AND f.user IS NULL")
    long countCompletedFilesByDeviceId(String deviceId);

    // 별점 5개 파일 수
    long countByRating(int rating);

    // userId별 별점 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.rating = :rating AND f.user.id = :userId")
    long countByRatingAndUserId(@Param("rating") int rating, @Param("userId") Long userId);

    // deviceId별 별점 파일 수
    long countByRatingAndDeviceIdAndUserIsNull(int rating, String deviceId);

    // ===== 추천용 메서드 =====

    // userId별 최근 읽은 파일
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt DESC")
    List<FileEntity> findTop50ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("userId") Long userId);

    // deviceId별 최근 읽은 파일
    List<FileEntity> findTop50ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(String deviceId);

    // ===== 히스토리용 =====

    @Query("""
        SELECT new com.ReadMe.demo.dto.HistoryFileDto(
            f.id, f.title, f.lastReadAt, f.rating, f.uri
        )
        FROM FileEntity f
        WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL
        ORDER BY f.lastReadAt DESC
    """)
    List<HistoryFileDto> findRecentFileDtosByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("""
        SELECT new com.ReadMe.demo.dto.HistoryFileDto(
            f.id, f.title, f.lastReadAt, f.rating, f.uri
        )
        FROM FileEntity f
        WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.lastReadAt IS NOT NULL
        ORDER BY f.lastReadAt DESC
    """)
    List<HistoryFileDto> findRecentFileDtosByDeviceId(@Param("deviceId") String deviceId, Pageable pageable);

    // 같은 제목의 이미 분석된 "본인" 파일 조회.
    // 예전에는 소유자 조건 없이 전역으로 찾아서 남의 파일 분석 결과가 복사됐다.
    @Query("""
        SELECT f FROM FileEntity f
        WHERE f.normalizedTitle = :normalizedTitle
          AND f.aiGenre IS NOT NULL
          AND f.id <> :excludeId
          AND f.user.id = :userId
        ORDER BY f.aiAnalyzedAt DESC
    """)
    List<FileEntity> findAnalyzedSameTitleByUserId(
            @Param("normalizedTitle") String normalizedTitle,
            @Param("excludeId") Long excludeId,
            @Param("userId") Long userId,
            Pageable pageable
    );

    @Query("""
        SELECT f FROM FileEntity f
        WHERE f.normalizedTitle = :normalizedTitle
          AND f.aiGenre IS NOT NULL
          AND f.id <> :excludeId
          AND f.deviceId = :deviceId AND f.user IS NULL
        ORDER BY f.aiAnalyzedAt DESC
    """)
    List<FileEntity> findAnalyzedSameTitleByDeviceId(
            @Param("normalizedTitle") String normalizedTitle,
            @Param("excludeId") Long excludeId,
            @Param("deviceId") String deviceId,
            Pageable pageable
    );

    /** 같은 제목의 분석 결과 재사용 - 반드시 본인(user 또는 device) 범위 안에서만 찾는다. */
    default FileEntity findOwnAnalyzedSameTitle(
            String normalizedTitle, Long excludeId, Long userId, String deviceId
    ) {
        if (normalizedTitle == null || normalizedTitle.isBlank() || excludeId == null) {
            return null;
        }

        Pageable one = org.springframework.data.domain.PageRequest.of(0, 1);
        List<FileEntity> found;
        if (userId != null) {
            found = findAnalyzedSameTitleByUserId(normalizedTitle, excludeId, userId, one);
        } else if (deviceId != null && !deviceId.isBlank()) {
            found = findAnalyzedSameTitleByDeviceId(normalizedTitle, excludeId, deviceId, one);
        } else {
            return null;
        }

        return found.isEmpty() ? null : found.get(0);
    }

    // userId와 path로 파일 삭제 (폴더 삭제 시)
    void deleteByUserAndPathIn(UserEntity user, List<Long> folderIds);

    // deviceId와 path로 파일 삭제 (폴더 삭제 시)
    void deleteByDeviceIdAndUserIsNullAndPathIn(String deviceId, List<Long> folderIds);

    // userId와 경로 리스트로 폴더 수 확인 (삭제 전 내부 파일 존재 여부 확인)
    long countByUserAndPathIn(UserEntity user, List<Long> paths);

    // deviceId와 경로 리스트로 폴더 수 확인 (삭제 전 내부 파일 존재 여부 확인)
    long countByDeviceIdAndUserIsNullAndPathIn(String deviceId, List<Long> paths);

    // deviceId를 userId와 연결
    @Modifying
    @Transactional
    @Query("UPDATE FileEntity f SET f.user.id = :userId WHERE f.deviceId = :deviceId AND f.user IS NULL")
    int linkDeviceToUser(@Param("deviceId") String deviceId, @Param("userId") Long userId);


    /****
     * 추천용 메서드
     * ****/

    // userId별 별점 높은 파일 (추천용)
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.rating >= :minRating AND f.aiGenre IS NOT NULL ORDER BY f.rating DESC")
    List<FileEntity> findHighRatedFilesByUserId(@Param("userId") Long userId, @Param("minRating") int minRating);

    // ===== 미분석 파일 조회 (추천 시 lazy 분석용) =====

    // userId별 미분석 파일
    @Query("""
        SELECT f FROM FileEntity f
        WHERE f.user.id = :userId
        AND (f.aiGenre IS NULL OR f.analysisStatus = 'PENDING' OR f.analysisStatus = 'FAILED')
        AND f.analysisStatus != 'SKIPPED'
        ORDER BY f.lastReadAt DESC
    """)
    List<FileEntity> findUnanalyzedFilesByUserId(@Param("userId") Long userId);

    // deviceId별 미분석 파일 (게스트)
    @Query("""
        SELECT f FROM FileEntity f
        WHERE f.deviceId = :deviceId AND f.user IS NULL
        AND (f.aiGenre IS NULL OR f.analysisStatus = 'PENDING' OR f.analysisStatus = 'FAILED')
        AND f.analysisStatus != 'SKIPPED'
        ORDER BY f.lastReadAt DESC
    """)
    List<FileEntity> findUnanalyzedFilesByDeviceId(@Param("deviceId") String deviceId);

    @Query("""
        SELECT new com.ReadMe.demo.dto.FileGenreKeywordDto(f.id, f.aiGenre, f.aiKeywords)
        FROM FileEntity f
        WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL
        ORDER BY f.lastReadAt DESC
    """)
    List<FileGenreKeywordDto> findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("userId") Long userId, Pageable pageable);

    @Query("""
        SELECT new com.ReadMe.demo.dto.FileGenreKeywordDto(f.id, f.aiGenre, f.aiKeywords)
        FROM FileEntity f
        WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.lastReadAt IS NOT NULL
        ORDER BY f.lastReadAt DESC
    """)
    List<FileGenreKeywordDto> findTop10ByDeviceIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("deviceId") String deviceId, Pageable pageable);

    // ===== 폴백용: 오래 전에 읽은 파일 재추천 =====

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt ASC")
    List<RecFileDto> findOldestReadFilesByUserId(@Param("userId") Long userId);

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt ASC")
    List<RecFileDto> findOldestReadFilesByDeviceId(@Param("deviceId") String deviceId);

    // ===== 추천 품질 판단용 =====

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId AND f.analysisStatus = 'DONE'")
    long countAnalyzedByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.analysisStatus = 'DONE'")
    long countAnalyzedByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId")
    long countAllByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL")
    long countAllByDeviceId(@Param("deviceId") String deviceId);

    // 안 읽은 파일 랜덤 추천 - PostgreSQL RANDOM()
    @Query(value = "SELECT f.id, f.title, f.uri, f.path, f.ai_genre, f.ai_keywords, f.progress, f.rating FROM files f WHERE f.user_id = :userId AND f.last_read_at IS NULL ORDER BY RANDOM()", nativeQuery = true)
    List<Object[]> findUnreadRandomByUserIdRaw(@Param("userId") Long userId);

    @Query(value = "SELECT f.id, f.title, f.uri, f.path, f.ai_genre, f.ai_keywords, f.progress, f.rating FROM files f WHERE f.device_id = :deviceId AND f.user_id IS NULL AND f.last_read_at IS NULL ORDER BY RANDOM()", nativeQuery = true)
    List<Object[]> findUnreadRandomByDeviceIdRaw(@Param("deviceId") String deviceId);

    // 최후 폴백: 전체에서 랜덤 1권
    @Query(value = "SELECT f.id, f.title, f.uri, f.path, f.ai_genre, f.ai_keywords, f.progress, f.rating FROM files f WHERE f.user_id = :userId ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    List<Object[]> findAnyRandomByUserIdRaw(@Param("userId") Long userId);

    @Query(value = "SELECT f.id, f.title, f.uri, f.path, f.ai_genre, f.ai_keywords, f.progress, f.rating FROM files f WHERE f.device_id = :deviceId AND f.user_id IS NULL ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    List<Object[]> findAnyRandomByDeviceIdRaw(@Param("deviceId") String deviceId);

    // ===== AI 분석 상태 =====
    // 상태는 조건부 UPDATE 한 번으로만 바꾼다. 조회 후 저장하면 그 사이 다른 요청이 바꾼 값을 덮어쓰고,
    // 여러 곳(워커·AI 정보 조회)이 같은 파일을 동시에 "내가 분석하겠다"고 판단할 수 있다.

    /** 분석을 요청한다. 아직 요청되지 않았거나 재시도할 파일만 QUEUED 로 바뀌고, 바뀐 경우에만 1을 돌려준다. */
    @Modifying
    @Transactional
    @Query("""
        UPDATE FileEntity f SET f.analysisStatus = 'QUEUED'
        WHERE f.id = :id
          AND (f.analysisStatus IS NULL OR f.analysisStatus IN ('PENDING', 'FAILED', 'LIMIT_EXCEEDED'))
    """)
    int markAnalysisQueued(@Param("id") Long id);

    /**
     * 분석 시작 권한을 가져간다. 1을 받은 한 곳만 분석한다.
     * 분석 중(PROCESSING)이어도 staleBefore 보다 오래됐으면 서버가 도중에 꺼진 것으로 보고 다시 가져갈 수 있다.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE FileEntity f SET f.analysisStatus = 'PROCESSING', f.analysisStartedAt = :now
        WHERE f.id = :id
          AND (f.analysisStatus IS NULL
               OR f.analysisStatus IN ('PENDING', 'QUEUED', 'FAILED', 'LIMIT_EXCEEDED')
               OR (f.analysisStatus = 'PROCESSING'
                   AND (f.analysisStartedAt IS NULL OR f.analysisStartedAt < :staleBefore)))
    """)
    int claimAnalysis(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore
    );

    /** 분석을 끝내지 못하고 내려놓는다. (FAILED / PENDING / LIMIT_EXCEEDED) */
    @Modifying
    @Transactional
    @Query("UPDATE FileEntity f SET f.analysisStatus = :status WHERE f.id = :id AND f.analysisStatus = 'PROCESSING'")
    int releaseAnalysis(@Param("id") Long id, @Param("status") String status);

    @Query("SELECT f.analysisStatus FROM FileEntity f WHERE f.id = :id")
    Optional<String> findAnalysisStatusById(@Param("id") Long id);

    // 방금 저장한 분석 결과를 응답할 때 쓴다. 엔티티 조회는 요청 초반에 읽은 값을 돌려줄 수 있어(open-in-view) 컬럼을 직접 읽는다.
    @Query("""
        SELECT new com.ReadMe.demo.dto.AiInfoResponse(
            f.aiGenre, f.aiKeywords, f.aiMood, f.aiSummary, f.aiTarget, f.analysisStatus
        )
        FROM FileEntity f WHERE f.id = :id
    """)
    Optional<AiInfoResponse> findAiInfoById(@Param("id") Long id);

    /**
     * 지금 프리미엄인 소유자의 파일 중 분석이 필요한 파일.
     * - 아직 분석 안 함 / 대기 중: 구독 전에 등록했거나, 서버 재시작으로 메모리 큐에서 사라진 파일
     * - 실패 / 한도 초과: 마지막 시도가 retryBefore(오늘 0시) 이전이면 하루 한 번 다시 시도
     * - 분석 중: staleBefore 보다 오래됐으면 서버가 도중에 꺼진 것
     * 프리미엄 판단은 SubscriptionService.isPremium(파일 소유 계정, 파일 등록 기기)과 같다.
     * 다르면 워커가 "프리미엄 아님"으로 되돌린 파일을 여기서 계속 다시 고르게 된다.
     */
    @Query("""
        SELECT f.id FROM FileEntity f
        WHERE (f.analysisStatus IS NULL
               OR f.analysisStatus IN ('PENDING', 'QUEUED')
               OR (f.analysisStatus IN ('FAILED', 'LIMIT_EXCEEDED')
                   AND (f.analysisStartedAt IS NULL OR f.analysisStartedAt < :retryBefore))
               OR (f.analysisStatus = 'PROCESSING'
                   AND (f.analysisStartedAt IS NULL OR f.analysisStartedAt < :staleBefore)))
          AND EXISTS (
              SELECT s.id FROM Subscription s
              WHERE s.status = com.ReadMe.demo.domain.enums.SubscriptionStatus.ACTIVE
                AND s.expiresAt > :now
                AND (s.user = f.user OR (s.user IS NULL AND s.deviceId = f.deviceId))
          )
        ORDER BY f.id
    """)
    List<Long> findAnalysisBacklogIds(
            @Param("now") Instant now,
            @Param("retryBefore") LocalDateTime retryBefore,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable
    );
}
