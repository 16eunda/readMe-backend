package com.ReadMe.demo.service;

import com.ReadMe.demo.exception.LoginBlockedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 아이디로 비밀번호를 연속해서 틀리면 잠시 로그인을 막는다. (비밀번호 대입 방지)
 * - 5번 연속 틀리면 10분 동안 막는다. 막힌 동안은 맞는 비밀번호여도 거절한다.
 * - 로그인에 성공하면, 또는 마지막 실패 후 10분이 지나면 처음부터 센다.
 * - 없는 아이디도 똑같이 센다. (있는 아이디인지 알려주지 않는다)
 * 서버가 한 대라 메모리에 둔다. 재시작하면 초기화되지만 그 정도는 괜찮다.
 * 한 IP 에서 여러 아이디를 돌아가며 시도하는 것은 nginx 의 IP 기준 제한(limit_req)이 막는다.
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(10);
    // 마지막 실패 후 이만큼 지나면 횟수를 처음부터 센다.
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    private record Attempts(int failures, Instant lastFailureAt, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /** 막혀 있으면 LoginBlockedException. 비밀번호를 확인하기 전에 부른다. */
    public void checkNotBlocked(String username) {
        Attempts current = attempts.get(username);
        Instant now = clock.instant();
        if (current != null && current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
            throw new LoginBlockedException(Duration.between(now, current.lockedUntil()));
        }
    }

    public void recordFailure(String username) {
        Instant now = clock.instant();
        attempts.compute(username, (key, current) -> {
            int failures = current == null || isStale(current, now) ? 1 : current.failures() + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
            return new Attempts(failures, now, lockedUntil);
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(username);
    }

    // 오래된 기록을 지운다. 없는 아이디로 계속 시도해도 메모리가 늘어나기만 하지 않게 한다.
    @Scheduled(fixedDelay = 600_000)
    public void purgeStale() {
        Instant now = clock.instant();
        attempts.values().removeIf(current -> isStale(current, now));
    }

    int trackedCount() {
        return attempts.size();
    }

    // 잠금 시간(10분)과 횟수 초기화 시간(10분)이 같아서, 오래된 기록이면 잠금도 이미 풀렸다.
    private static boolean isStale(Attempts current, Instant now) {
        return !now.isBefore(current.lastFailureAt().plus(FAILURE_WINDOW));
    }
}
