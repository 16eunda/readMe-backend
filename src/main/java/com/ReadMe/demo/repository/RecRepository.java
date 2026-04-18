package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecRepository extends JpaRepository<FileEntity, Long> {
    // ===== userId 필터링 (로그인 사용자) =====

    // 특정 장르이면서 안 읽은 파일
    @Query("SELECT f FROM FileEntity f WHERE f.aiGenre = :genre AND f.lastReadAt IS NULL AND f.user.id = :userId")
    List<FileEntity> findByAiGenreAndLastReadAtIsNullAndUserId(@Param("genre") String genre, @Param("userId") Long userId);

    // 장르 상관없이 안 읽은 파일 (별점 높은 순)
    @Query("SELECT f FROM FileEntity f WHERE f.lastReadAt IS NULL AND f.user.id = :userId ORDER BY f.rating DESC")
    List<FileEntity> findByLastReadAtIsNullAndUserIdOrderByRatingDesc(@Param("userId") Long userId);

    // 진행 중인 파일 (10~90%)
    @Query("SELECT f FROM FileEntity f WHERE f.progress BETWEEN :min AND :max AND f.user.id = :userId")
    List<FileEntity> findByProgressBetweenAndUserId(@Param("min") double min, @Param("max") double max, @Param("userId") Long userId);

    // ===== deviceId 필터링 (게스트) =====

    // 특정 장르이면서 안 읽은 파일
    List<FileEntity> findByAiGenreAndLastReadAtIsNullAndDeviceIdAndUserIsNull(String genre, String deviceId);

    // 장르 상관없이 안 읽은 파일 (별점 높은 순)
    List<FileEntity> findByLastReadAtIsNullAndDeviceIdAndUserIsNullOrderByRatingDesc(String deviceId);

    // 진행 중인 파일 (10~90%)
    List<FileEntity> findByProgressBetweenAndDeviceIdAndUserIsNull(double min, double max, String deviceId);
}
