package com.ReadMe.demo.exception;

import java.time.Duration;

/**
 * 같은 아이디로 비밀번호를 연속해서 틀려 로그인이 잠시 막힌 상태. 429 로 응답한다.
 */
public class LoginBlockedException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginBlockedException(Duration remaining) {
        super(message(remaining));
        this.retryAfterSeconds = Math.max(1, remaining.toSeconds());
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(Duration remaining) {
        long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
        return "로그인 시도가 너무 많습니다. " + minutes + "분 후 다시 시도해 주세요.";
    }
}
