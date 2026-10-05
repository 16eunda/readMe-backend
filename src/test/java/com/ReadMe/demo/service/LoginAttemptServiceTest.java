package com.ReadMe.demo.service;

import com.ReadMe.demo.exception.LoginBlockedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void blockLiftsAfterTenMinutesWithFreshAttempts() {
        failTimes("reader", 5);
        assertThatThrownBy(() -> service.checkNotBlocked("reader"))
                .isInstanceOf(LoginBlockedException.class)
                .hasMessageContaining("10분 후");

        clock.advance(Duration.ofMinutes(9));
        assertThatThrownBy(() -> service.checkNotBlocked("reader")).hasMessageContaining("1분 후");

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> service.checkNotBlocked("reader")).doesNotThrowAnyException();

        // 잠금이 풀린 뒤에는 다시 5번의 기회가 있다.
        failTimes("reader", 4);
        assertThatCode(() -> service.checkNotBlocked("reader")).doesNotThrowAnyException();
    }

    // 며칠에 걸친 오타는 잠금으로 이어지지 않는다.
    @Test
    void failuresFarApartDoNotAddUp() {
        for (int i = 0; i < 10; i++) {
            service.recordFailure("reader");
            clock.advance(Duration.ofMinutes(11));
        }
        assertThatCode(() -> service.checkNotBlocked("reader")).doesNotThrowAnyException();
    }

    @Test
    void purgeForgetsOldRecords() {
        failTimes("a", 5);
        failTimes("b", 1);
        clock.advance(Duration.ofMinutes(10));

        service.purgeStale();

        assertThat(service.trackedCount()).isZero();
    }

    private void failTimes(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
