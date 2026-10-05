package com.ReadMe.demo.worker;

import com.ReadMe.demo.repository.RefreshSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 30일 동안 쓰지 않아 만료된 로그인 세션을 하루 한 번 지운다.
 * (만료된 세션은 이미 재발급에 쓸 수 없다. 테이블이 계속 커지지 않게 정리만 한다)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshSessionCleanupScheduler {

    private final RefreshSessionRepository refreshSessionRepository;

    @Scheduled(cron = "${auth.session-cleanup-cron:0 45 3 * * *}")
    @Transactional
    public void deleteExpiredSessions() {
        int deleted = refreshSessionRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("만료된 로그인 세션 {}건을 지웠습니다.", deleted);
        }
    }
}
