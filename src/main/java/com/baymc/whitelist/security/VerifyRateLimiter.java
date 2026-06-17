package com.baymc.whitelist.security;

import com.baymc.whitelist.config.PluginConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks failed /whitelist attempts in memory and temporarily locks noisy players or IPs.
 */
public final class VerifyRateLimiter {
    private final PluginConfig.VerifyRateLimitSettings settings;
    private final Clock clock;
    private final Map<String, Bucket> playerBuckets = new HashMap<>();
    private final Map<String, Bucket> ipBuckets = new HashMap<>();
    private final Map<String, Instant> notificationTimes = new HashMap<>();

    public VerifyRateLimiter(PluginConfig.VerifyRateLimitSettings settings) {
        this(settings, Clock.systemUTC());
    }

    public VerifyRateLimiter(PluginConfig.VerifyRateLimitSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public PluginConfig.VerifyRateLimitSettings settings() {
        return settings;
    }

    public synchronized Decision check(String playerKey, String ip) {
        if (!settings.enabled()) {
            return Decision.allowed();
        }

        Instant now = clock.instant();
        if (settings.playerEnabled()) {
            Decision playerDecision = lockedDecision(playerBuckets.get(playerKey), Scope.PLAYER, now);
            if (playerDecision.status() == Status.LOCKED) {
                return playerDecision;
            }
        }

        if (settings.ipEnabled() && !isBlank(ip)) {
            Decision ipDecision = lockedDecision(ipBuckets.get(ip), Scope.IP, now);
            if (ipDecision.status() == Status.LOCKED) {
                return ipDecision;
            }
        }
        return Decision.allowed();
    }

    public synchronized Decision recordFailure(String playerKey, String ip) {
        if (!settings.enabled()) {
            return Decision.allowed();
        }

        Instant now = clock.instant();
        if (settings.playerEnabled()) {
            boolean playerLimited = recordFailure(playerBuckets.computeIfAbsent(playerKey, key -> new Bucket(now)), now,
                    settings.playerWindowSeconds(), settings.maxFailuresPerPlayer());
            if (playerLimited) {
                return Decision.rateLimited(Scope.PLAYER, settings.lockSeconds());
            }
        }

        if (settings.ipEnabled() && !isBlank(ip)) {
            boolean ipLimited = recordFailure(ipBuckets.computeIfAbsent(ip, key -> new Bucket(now)), now,
                    settings.ipWindowSeconds(), settings.maxFailuresPerIp());
            if (ipLimited) {
                return Decision.rateLimited(Scope.IP, settings.lockSeconds());
            }
        }
        return Decision.allowed();
    }

    public synchronized void reset(String playerKey, String ip) {
        playerBuckets.remove(playerKey);
        if (!isBlank(ip)) {
            ipBuckets.remove(ip);
        }
    }

    public synchronized boolean shouldNotify(Scope scope, String playerKey, String ip) {
        String key = notificationKey(scope, playerKey, ip);
        if (key == null) {
            return false;
        }

        Instant now = clock.instant();
        Instant lastNotifiedAt = notificationTimes.get(key);
        if (lastNotifiedAt != null
                && lastNotifiedAt.plusSeconds(settings.notifyIntervalSeconds()).isAfter(now)) {
            return false;
        }
        notificationTimes.put(key, now);
        return true;
    }

    private Decision lockedDecision(Bucket bucket, Scope scope, Instant now) {
        if (bucket == null || bucket.lockedUntil == null) {
            return Decision.allowed();
        }
        if (!bucket.lockedUntil.isAfter(now)) {
            bucket.lockedUntil = null;
            bucket.failureCount = 0;
            bucket.windowStartedAt = now;
            return Decision.allowed();
        }

        boolean shouldLogBlocked = bucket.lastBlockedLogAt == null
                || !bucket.lastBlockedLogAt.plusSeconds(settings.blockedLogIntervalSeconds()).isAfter(now);
        if (shouldLogBlocked) {
            bucket.lastBlockedLogAt = now;
        }
        return Decision.locked(scope, remainingSeconds(now, bucket.lockedUntil), shouldLogBlocked);
    }

    private boolean recordFailure(Bucket bucket, Instant now, int windowSeconds, int maxFailures) {
        if (bucket.lockedUntil != null && bucket.lockedUntil.isAfter(now)) {
            return false;
        }
        if (bucket.windowStartedAt.plusSeconds(windowSeconds).isBefore(now)
                || bucket.windowStartedAt.plusSeconds(windowSeconds).equals(now)) {
            bucket.windowStartedAt = now;
            bucket.failureCount = 0;
            bucket.lockedUntil = null;
            bucket.lastBlockedLogAt = null;
        }

        bucket.failureCount++;
        if (bucket.failureCount >= maxFailures) {
            bucket.lockedUntil = now.plusSeconds(settings.lockSeconds());
            bucket.lastBlockedLogAt = null;
            return true;
        }
        return false;
    }

    private static long remainingSeconds(Instant now, Instant until) {
        long millis = Duration.between(now, until).toMillis();
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String notificationKey(Scope scope, String playerKey, String ip) {
        return switch (scope) {
            case PLAYER -> isBlank(playerKey) ? null : "player:" + playerKey;
            case IP -> isBlank(ip) ? null : "ip:" + ip;
        };
    }

    public enum Status {
        ALLOWED,
        LOCKED,
        RATE_LIMITED
    }

    public enum Scope {
        PLAYER,
        IP
    }

    public record Decision(Status status, Scope scope, long remainingSeconds, boolean shouldLogBlocked) {
        public static Decision allowed() {
            return new Decision(Status.ALLOWED, null, 0L, false);
        }

        static Decision locked(Scope scope, long remainingSeconds, boolean shouldLogBlocked) {
            return new Decision(Status.LOCKED, scope, remainingSeconds, shouldLogBlocked);
        }

        static Decision rateLimited(Scope scope, long remainingSeconds) {
            return new Decision(Status.RATE_LIMITED, scope, remainingSeconds, false);
        }
    }

    private static final class Bucket {
        private Instant windowStartedAt;
        private int failureCount;
        private Instant lockedUntil;
        private Instant lastBlockedLogAt;

        private Bucket(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
