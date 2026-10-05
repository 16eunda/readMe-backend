package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.RefreshSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {

    /**
     * 살아 있는 세션이면 만료를 늦추고 1을 돌려준다. 없거나(로그아웃·탈퇴) 만료됐으면 0.
     * 확인과 연장을 UPDATE 한 번으로 해서, 같은 토큰으로 동시에 재발급해도, 재발급과 로그아웃이 겹쳐도 어긋나지 않는다.
     */
    @Modifying
    @Query("""
        UPDATE RefreshSession s SET s.expiresAt = :newExpiresAt
        WHERE s.id = :id AND s.user.id = :userId AND s.expiresAt > :now
    """)
    int extendIfActive(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("now") Instant now,
            @Param("newExpiresAt") Instant newExpiresAt
    );

    @Modifying
    @Query("DELETE FROM RefreshSession s WHERE s.id = :id AND s.user.id = :userId")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefreshSession s WHERE s.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefreshSession s WHERE s.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
