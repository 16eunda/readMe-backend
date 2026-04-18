package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileReadLog;
import com.ReadMe.demo.dto.FileRankingDto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileReadLogRepository extends JpaRepository<FileReadLog, Long> {

    // userId로 필터링된 랭킹 (로그인 사용자)
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.uri,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :from AND f.user.id = :userId
        GROUP BY f.id, f.title, f.progress, f.rating, f.uri
        ORDER BY COUNT(r.id) DESC, MAX(r.readAt) DESC
    """)
    List<FileRankingDto> findRankingSinceByUserId(
            @Param("from") LocalDateTime from,
            @Param("userId") Long userId,
            Pageable pageable
    );

    // deviceId로 필터링된 랭킹 (게스트)
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.uri,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :from AND f.deviceId = :deviceId AND f.user IS NULL
        GROUP BY f.id, f.title, f.progress, f.rating, f.uri
        ORDER BY COUNT(r.id) DESC, MAX(r.readAt) DESC
    """)
    List<FileRankingDto> findRankingSinceByDeviceId(
            @Param("from") LocalDateTime from,
            @Param("deviceId") String deviceId,
            Pageable pageable
    );

    // 특정 월/년 랭킹 조회 (로그인 사용자)
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.uri,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :start AND r.readAt < :end AND f.user.id = :userId
        GROUP BY f.id, f.title, f.progress, f.rating, f.uri
        ORDER BY COUNT(r.id) DESC, MAX(r.readAt) DESC
    """)
    List<FileRankingDto> findRankingByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable
    );

    // 특정 월/년 랭킹 조회 (게스트)
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.uri,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :start AND r.readAt < :end AND f.deviceId = :deviceId AND f.user IS NULL
        GROUP BY f.id, f.title, f.progress, f.rating, f.uri
        ORDER BY COUNT(r.id) DESC, MAX(r.readAt) DESC
    """)
    List<FileRankingDto> findRankingByDeviceIdAndDateRange(
            @Param("deviceId") String deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable
    );

    // 기존 메서드 유지 (하위 호환성 - 사용 중단 예정)
    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.uri,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :from
        GROUP BY f.id, f.title, f.progress, f.rating, f.uri
        ORDER BY COUNT(r.id) DESC
    """)
    @Deprecated
    List<FileRankingDto> findRankingSince(
            @Param("from") LocalDateTime from,
            Pageable pageable
    );


    // 오늘 날짜에 해당 파일의 로그가 있는지 확인
    @Query("""
        SELECT r FROM FileReadLog r
        WHERE r.file.id = :fileId
        AND r.readAt >= :startOfDay
        AND r.readAt < :startOfNextDay
    """)
    Optional<FileReadLog> findByFileIdAndToday(
            @Param("fileId") Long fileId,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("startOfNextDay") LocalDateTime startOfNextDay
    );
}

