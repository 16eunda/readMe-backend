package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.FileType;
import com.ReadMe.demo.domain.UserEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    // 파일 경로로 조회
    Page<FileEntity> findByPath(String path, Pageable pageable);

    // 추가: deviceId로 필터링 (게스트용)
    Page<FileEntity> findByPathAndDeviceIdAndUserIsNull(
            String path,
            String deviceId,
            Pageable pageable
    );

    // 추가: userId로 필터링 (로그인용 - 모든 기기 파일)
    @Query("SELECT f FROM FileEntity f WHERE f.path = :path AND f.user.id = :userId")
    Page<FileEntity> findByPathAndUserId(
            @Param("path") String path,
            @Param("userId") String userId,
            Pageable pageable
    );

    // 추가: userId와 id 리스트로 파일 삭제 (보안 필터링)
    void deleteByUserAndIdIn(UserEntity user, List<Long> ids);

    // 추가: deviceId와 id 리스트로 파일 삭제 (보안 필터링, 게스트)
    void deleteByDeviceIdAndUserIsNullAndIdIn(String deviceId, List<Long> ids);

    // 중복 확인
    Boolean existsByTitleAndPath(String title, String path);

    // 최근 읽은 파일 (히스토리)
    List<FileEntity> findTop50ByLastReadAtIsNotNullOrderByLastReadAtDesc();

    // 검색 메서드 추가
    Page<FileEntity> findByUserAndTitleContainingIgnoreCase(
            String keyword, UserEntity user, Pageable pageable
    );

    Page<FileEntity> findByDeviceIdAndUserIsNullAndTitleContainingIgnoreCase(
            String keyword, String deviceId, Pageable pageable
    );

    // ===== 통계용 메서드 (userId 필터링) =====

    // 전체 파일 수
    long count();

    // userId별 전체 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    // deviceId별 전체 파일 수 (게스트)
    long countByDeviceIdAndUserIsNull(String deviceId);

    // userId별 완독 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.completed = true AND f.user.id = :userId")
    long countCompletedFilesByUserId(@Param("userId") Long userId);

    // deviceId별 완독 파일 수 (게스트)
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.completed = true AND f.deviceId = :deviceId AND f.user IS NULL")
    long countCompletedFilesByDeviceIdAndUserIsNull(String deviceId);

    // 별점 5개 파일 수
    long countByRating(int rating);

    // userId별 별점 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.rating = :rating AND f.user.id = :userId")
    long countByRatingAndUserId(@Param("rating") int rating, @Param("userId") Long userId);

    // deviceId별 별점 파일 수 (게스트)
    long countByRatingAndDeviceIdAndUserIsNull(int rating, String deviceId);

    // ===== 추천용 메서드 =====

    // userId별 최근 읽은 파일
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt DESC")
    List<FileEntity> findTop50ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("userId") Long userId);

    // deviceId별 최근 읽은 파일 (게스트)
    List<FileEntity> findTop50ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(String deviceId);

    // AI 장르가 있는 파일 수 (이미 분석된 파일이 있는지 확인용)
    FileEntity findFirstByNormalizedTitleAndAiGenreIsNotNullAndIdNot(
            String normalizedTitle, Long id
    );

    // userId와 path로 파일 삭제 (폴더 삭제 시)
    void deleteByUserAndPathIn(UserEntity user, List<Long> folderIds);

    // deviceId와 path로 파일 삭제 (폴더 삭제 시, 게스트)
    void deleteByDeviceIdAndUserIsNullAndPathIn(String deviceId, List<Long> folderIds);

    // userId와 경로 리스트로 폴더 수 확인 (삭제 전 내부 파일 존재 여부 확인)
    long countByUserAndPathIn(UserEntity user, List<Long> paths);

    // deviceId와 경로 리스트로 폴더 수 확인 (삭제 전 내부 파일 존재 여부 확인, 게스트)
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

    // userId별 미분석 파일 (aiGenre가 null이고 analysisStatus가 DONE이 아닌 파일)
    @Query("""
        SELECT f FROM FileEntity f 
        WHERE f.user.id = :userId 
        AND (f.aiGenre IS NULL OR f.analysisStatus = 'PENDING' OR f.analysisStatus = 'FAILED')
        AND f.analysisStatus != 'SKIPPED'
        ORDER BY f.lastReadAt DESC NULLS LAST
    """)
    List<FileEntity> findUnanalyzedFilesByUserId(@Param("userId") Long userId);

    // deviceId별 미분석 파일 (게스트)
    @Query("""
        SELECT f FROM FileEntity f 
        WHERE f.deviceId = :deviceId AND f.user IS NULL 
        AND (f.aiGenre IS NULL OR f.analysisStatus = 'PENDING' OR f.analysisStatus = 'FAILED')
        AND f.analysisStatus != 'SKIPPED'
        ORDER BY f.lastReadAt DESC NULLS LAST
    """)
    List<FileEntity> findUnanalyzedFilesByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt DESC LIMIT 10")
    List<FileEntity> findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(@Param("userId") Long userId);

    List<FileEntity> findTop10ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(String deviceId);

    // ===== 폴백용: 오래 전에 읽은 파일 재추천 =====

    // 가장 오래 전에 읽은 파일 (다시 읽어볼 만한 파일 추천)
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt ASC")
    List<FileEntity> findOldestReadFilesByUserId(@Param("userId") Long userId);

    // 게스트용
    @Query("SELECT f FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.lastReadAt IS NOT NULL ORDER BY f.lastReadAt ASC")
    List<FileEntity> findOldestReadFilesByDeviceId(@Param("deviceId") String deviceId);

    // ===== 추천 품질 판단용 =====

    // 분석 완료된 파일 수
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId AND f.analysisStatus = 'DONE'")
    long countAnalyzedByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.analysisStatus = 'DONE'")
    long countAnalyzedByDeviceId(@Param("deviceId") String deviceId);

    // 전체 파일 수 (userId)
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId")
    long countAllByUserId(@Param("userId") Long userId);

    // 전체 파일 수 (deviceId)
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL")
    long countAllByDeviceId(@Param("deviceId") String deviceId);

    // 안 읽은 파일 랜덤 추천 (분석 여부 상관없이)
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.lastReadAt IS NULL ORDER BY FUNCTION('RAND')")
    List<FileEntity> findUnreadRandomByUserId(@Param("userId") Long userId);

    @Query("SELECT f FROM FileEntity f WHERE f.deviceId = :deviceId AND f.user IS NULL AND f.lastReadAt IS NULL ORDER BY FUNCTION('RAND')")
    List<FileEntity> findUnreadRandomByDeviceId(@Param("deviceId") String deviceId);
}