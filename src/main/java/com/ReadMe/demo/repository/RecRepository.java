package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.dto.RecFileDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecRepository extends JpaRepository<FileEntity, Long> {

    // ===== userId 필터링 (로그인 사용자) =====

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.aiGenre = :genre AND f.lastReadAt IS NULL AND f.user.id = :userId")
    List<RecFileDto> findByAiGenreAndLastReadAtIsNullAndUserId(@Param("genre") String genre, @Param("userId") Long userId);

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.lastReadAt IS NULL AND f.user.id = :userId ORDER BY f.rating DESC")
    List<RecFileDto> findByLastReadAtIsNullAndUserIdOrderByRatingDesc(@Param("userId") Long userId);

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.progress BETWEEN :min AND :max AND f.user.id = :userId")
    List<RecFileDto> findByProgressBetweenAndUserId(@Param("min") double min, @Param("max") double max, @Param("userId") Long userId);

    // ===== deviceId 필터링 (게스트) =====

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.aiGenre = :genre AND f.lastReadAt IS NULL AND f.deviceId = :deviceId AND f.user IS NULL")
    List<RecFileDto> findByAiGenreAndLastReadAtIsNullAndDeviceIdAndUserIsNull(@Param("genre") String genre, @Param("deviceId") String deviceId);

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.lastReadAt IS NULL AND f.deviceId = :deviceId AND f.user IS NULL ORDER BY f.rating DESC")
    List<RecFileDto> findByLastReadAtIsNullAndDeviceIdAndUserIsNullOrderByRatingDesc(@Param("deviceId") String deviceId);

    @Query("SELECT new com.ReadMe.demo.dto.RecFileDto(f.id, f.title, f.uri, f.path, f.aiGenre, f.aiKeywords, f.progress, f.rating) FROM FileEntity f WHERE f.progress BETWEEN :min AND :max AND f.deviceId = :deviceId AND f.user IS NULL")
    List<RecFileDto> findByProgressBetweenAndDeviceIdAndUserIsNull(@Param("min") double min, @Param("max") double max, @Param("deviceId") String deviceId);
}
