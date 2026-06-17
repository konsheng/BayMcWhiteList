package com.baymc.whitelist.security;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifyRateLimiterTest {
    private static final String PLAYER_KEY = "player-one";
    private static final String IP = "127.0.0.1";

    @Test
    void allowsFailuresBelowPlayerThreshold() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.recordFailure(PLAYER_KEY, IP).status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.recordFailure(PLAYER_KEY, IP).status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_KEY, IP).status());
    }

    @Test
    void locksPlayerWhenPlayerThresholdIsReached() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);
        VerifyRateLimiter.Decision limited = limiter.recordFailure(PLAYER_KEY, IP);
        VerifyRateLimiter.Decision locked = limiter.check(PLAYER_KEY, IP);

        assertEquals(VerifyRateLimiter.Status.RATE_LIMITED, limited.status());
        assertEquals(VerifyRateLimiter.Scope.PLAYER, limited.scope());
        assertEquals(600L, limited.remainingSeconds());
        assertEquals(VerifyRateLimiter.Status.LOCKED, locked.status());
        assertEquals(VerifyRateLimiter.Scope.PLAYER, locked.scope());
        assertEquals(600L, locked.remainingSeconds());
    }

    @Test
    void locksIpWhenIpThresholdIsReached() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, 10, 300, 2, 300, 600, true, 60));

        limiter.recordFailure("player-a", IP);
        VerifyRateLimiter.Decision limited = limiter.recordFailure("player-b", IP);

        assertEquals(VerifyRateLimiter.Status.RATE_LIMITED, limited.status());
        assertEquals(VerifyRateLimiter.Scope.IP, limited.scope());
        assertEquals(VerifyRateLimiter.Status.LOCKED, limiter.check("player-c", IP).status());
    }

    @Test
    void resetClearsPlayerAndIpFailures() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.reset(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_KEY, IP).status());
    }

    @Test
    void blockedLogIntervalControlsRepeatedBlockedLogs() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);

        VerifyRateLimiter.Decision firstBlocked = limiter.check(PLAYER_KEY, IP);
        VerifyRateLimiter.Decision secondBlocked = limiter.check(PLAYER_KEY, IP);
        clock.advanceSeconds(60);
        VerifyRateLimiter.Decision thirdBlocked = limiter.check(PLAYER_KEY, IP);

        assertEquals(true, firstBlocked.shouldLogBlocked());
        assertEquals(false, secondBlocked.shouldLogBlocked());
        assertEquals(true, thirdBlocked.shouldLogBlocked());
    }

    @Test
    void disabledLimiterAlwaysAllowsAttempts() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(false, 1, 300, 1, 300, 600, true, 60));

        limiter.recordFailure(PLAYER_KEY, IP);
        limiter.recordFailure(PLAYER_KEY, IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_KEY, IP).status());
    }

    private static VerifyRateLimiter limiter(MutableClock clock) {
        return limiter(clock, settings(true, 3, 300, 20, 300, 600, true, 60));
    }

    private static VerifyRateLimiter limiter(MutableClock clock, PluginConfig.VerifyRateLimitSettings settings) {
        return new VerifyRateLimiter(settings, clock);
    }

    private static PluginConfig.VerifyRateLimitSettings settings(
            boolean enabled,
            int maxFailuresPerPlayer,
            int playerWindowSeconds,
            int maxFailuresPerIp,
            int ipWindowSeconds,
            int lockSeconds,
            boolean kickOnLock,
            int blockedLogIntervalSeconds
    ) {
        return new PluginConfig.VerifyRateLimitSettings(
                enabled,
                maxFailuresPerPlayer,
                playerWindowSeconds,
                maxFailuresPerIp,
                ipWindowSeconds,
                lockSeconds,
                kickOnLock,
                blockedLogIntervalSeconds
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-06-17T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
