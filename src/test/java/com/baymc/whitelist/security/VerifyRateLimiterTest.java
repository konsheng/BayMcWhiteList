package com.baymc.whitelist.security;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifyRateLimiterTest {
    private static final String PLAYER_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String IP = "127.0.0.1";

    @Test
    void allowsFailuresBelowPlayerThreshold() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.recordFailure(PLAYER_UUID, IP).status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.recordFailure(PLAYER_UUID, IP).status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_UUID, IP).status());
    }

    @Test
    void locksPlayerWhenPlayerThresholdIsReached() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);
        VerifyRateLimiter.Decision limited = limiter.recordFailure(PLAYER_UUID, IP);
        VerifyRateLimiter.Decision locked = limiter.check(PLAYER_UUID, IP);

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
        VerifyRateLimiter limiter = limiter(clock, settings(true, true, 10, 300, true, 2, 300, 600, true, 60, 60));

        limiter.recordFailure("player-a", IP);
        VerifyRateLimiter.Decision limited = limiter.recordFailure("player-b", IP);

        assertEquals(VerifyRateLimiter.Status.RATE_LIMITED, limited.status());
        assertEquals(VerifyRateLimiter.Scope.IP, limited.scope());
        assertEquals(VerifyRateLimiter.Status.LOCKED, limiter.check("player-c", IP).status());
    }

    @Test
    void playerLimitCanBeDisabledIndependently() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, false, 1, 300, true, 20, 300, 600, true, 60, 60));

        limiter.recordFailure(PLAYER_UUID, "127.0.0.1");
        VerifyRateLimiter.Decision secondFailure = limiter.recordFailure(PLAYER_UUID, "127.0.0.2");

        assertEquals(VerifyRateLimiter.Status.ALLOWED, secondFailure.status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_UUID, "127.0.0.2").status());
    }

    @Test
    void ipLimitCanBeDisabledIndependently() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, true, 10, 300, false, 1, 300, 600, true, 60, 60));

        limiter.recordFailure("player-a", IP);
        VerifyRateLimiter.Decision secondFailure = limiter.recordFailure("player-b", IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, secondFailure.status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check("player-c", IP).status());
    }

    @Test
    void disabledPlayerAndIpDimensionsAllowAttemptsWhenMasterSwitchIsEnabled() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, false, 1, 300, false, 1, 300, 600, true, 60, 60));

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);
        VerifyRateLimiter.Decision thirdFailure = limiter.recordFailure(PLAYER_UUID, IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, thirdFailure.status());
        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_UUID, IP).status());
    }

    @Test
    void playerScopeTakesPriorityWhenBothScopesWouldLimit() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, true, 1, 300, true, 1, 300, 600, true, 60, 60));

        VerifyRateLimiter.Decision limited = limiter.recordFailure(PLAYER_UUID, IP);

        assertEquals(VerifyRateLimiter.Status.RATE_LIMITED, limited.status());
        assertEquals(VerifyRateLimiter.Scope.PLAYER, limited.scope());
    }

    @Test
    void resetClearsPlayerAndIpFailures() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.reset(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_UUID, IP).status());
    }

    @Test
    void blockedLogIntervalControlsRepeatedBlockedLogs() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);

        VerifyRateLimiter.Decision firstBlocked = limiter.check(PLAYER_UUID, IP);
        VerifyRateLimiter.Decision secondBlocked = limiter.check(PLAYER_UUID, IP);
        clock.advanceSeconds(60);
        VerifyRateLimiter.Decision thirdBlocked = limiter.check(PLAYER_UUID, IP);

        assertEquals(true, firstBlocked.shouldLogBlocked());
        assertEquals(false, secondBlocked.shouldLogBlocked());
        assertEquals(true, thirdBlocked.shouldLogBlocked());
    }

    @Test
    void disabledLimiterAlwaysAllowsAttempts() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(false, true, 1, 300, true, 1, 300, 600, true, 60, 60));

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.recordFailure(PLAYER_UUID, IP);

        assertEquals(VerifyRateLimiter.Status.ALLOWED, limiter.check(PLAYER_UUID, IP).status());
    }

    @Test
    void notificationCooldownSuppressesRepeatedScopeNotification() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        assertEquals(true, limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP));
        assertEquals(false, limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP));
        clock.advanceSeconds(60);
        assertEquals(true, limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP));
    }

    @Test
    void notificationCooldownSeparatesPlayerAndIpScopes() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock);

        assertEquals(true, limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP));
        assertEquals(true, limiter.shouldNotify(VerifyRateLimiter.Scope.IP, PLAYER_UUID, IP));
        assertEquals(false, limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP));
        assertEquals(false, limiter.shouldNotify(VerifyRateLimiter.Scope.IP, PLAYER_UUID, IP));
    }

    @Test
    void expiredFailureAndNotificationEntriesArePruned() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, true, 10, 10, true, 10, 10, 600, true, 60, 10));

        limiter.recordFailure(PLAYER_UUID, IP);
        limiter.shouldNotify(VerifyRateLimiter.Scope.PLAYER, PLAYER_UUID, IP);
        assertEquals(3, limiter.trackedEntryCount());

        clock.advanceSeconds(10);
        limiter.check("another-player", "127.0.0.2");

        assertEquals(0, limiter.trackedEntryCount());
    }

    @Test
    void expiredLockedEntriesArePruned() {
        MutableClock clock = new MutableClock();
        VerifyRateLimiter limiter = limiter(clock, settings(true, true, 1, 300, true, 20, 300, 10, true, 60, 60));

        limiter.recordFailure(PLAYER_UUID, IP);
        assertEquals(1, limiter.trackedEntryCount());

        clock.advanceSeconds(10);
        limiter.check("another-player", "127.0.0.2");

        assertEquals(0, limiter.trackedEntryCount());
    }

    private static VerifyRateLimiter limiter(MutableClock clock) {
        return limiter(clock, settings(true, true, 3, 300, true, 20, 300, 600, true, 60, 60));
    }

    private static VerifyRateLimiter limiter(MutableClock clock, PluginConfig.VerifyRateLimitSettings settings) {
        return new VerifyRateLimiter(settings, clock);
    }

    private static PluginConfig.VerifyRateLimitSettings settings(
            boolean enabled,
            boolean playerEnabled,
            int maxFailuresPerPlayer,
            int playerWindowSeconds,
            boolean ipEnabled,
            int maxFailuresPerIp,
            int ipWindowSeconds,
            int lockSeconds,
            boolean kickOnLock,
            int blockedLogIntervalSeconds,
            int notifyIntervalSeconds
    ) {
        return new PluginConfig.VerifyRateLimitSettings(
                enabled,
                playerEnabled,
                maxFailuresPerPlayer,
                playerWindowSeconds,
                ipEnabled,
                maxFailuresPerIp,
                ipWindowSeconds,
                lockSeconds,
                kickOnLock,
                blockedLogIntervalSeconds,
                true,
                true,
                "baymcwhitelist.notify",
                notifyIntervalSeconds
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
