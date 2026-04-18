package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.AiAnalysisLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AiAnalysisLogRepository extends JpaRepository<AiAnalysisLog, Long> {

    // 로그인 유저의 오늘 사용 횟수
    @Query("SELECT COUNT(l) FROM AiAnalysisLog l WHERE l.user.id = :userId AND l.analyzedAt >= :startOfDay")
    long countTodayByUserId(@Param("userId") Long userId, @Param("startOfDay") LocalDateTime startOfDay);

    // 게스트의 오늘 사용 횟수
    @Query("SELECT COUNT(l) FROM AiAnalysisLog l WHERE l.deviceId = :deviceId AND l.user IS NULL AND l.analyzedAt >= :startOfDay")
    long countTodayByDeviceId(@Param("deviceId") String deviceId, @Param("startOfDay") LocalDateTime startOfDay);
}
