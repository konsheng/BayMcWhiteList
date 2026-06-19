package com.baymc.whitelist.security;

import com.baymc.whitelist.config.PluginConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 在内存中统计 /whitelist 验证失败次数, 并按玩家 UUID 或 IP 临时锁定高频失败来源
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

    public synchronized Decision check(String playerUuid, String ip) {
        if (!settings.enabled()) {
            return Decision.allowed();
        }

        Instant now = clock.instant();
        pruneExpired(now);
        if (settings.playerEnabled()) {
            Decision playerDecision = lockedDecision(playerBuckets.get(playerUuid), Scope.PLAYER, now);
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

    public synchronized Decision recordFailure(String playerUuid, String ip) {
        if (!settings.enabled()) {
            return Decision.allowed();
        }

        Instant now = clock.instant();
        pruneExpired(now);
        if (settings.playerEnabled()) {
            boolean playerLimited = recordFailure(playerBuckets.computeIfAbsent(playerUuid, key -> new Bucket(now)), now,
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

    public synchronized void reset(String playerUuid, String ip) {
        playerBuckets.remove(playerUuid);
        if (!isBlank(ip)) {
            ipBuckets.remove(ip);
        }
    }

    public synchronized boolean shouldNotify(Scope scope, String playerUuid, String ip) {
        String key = notificationKey(scope, playerUuid, ip);
        if (key == null) {
            return false;
        }

        Instant now = clock.instant();
        pruneExpired(now);
        Instant lastNotifiedAt = notificationTimes.get(key);
        if (lastNotifiedAt != null
                && lastNotifiedAt.plusSeconds(settings.notifyIntervalSeconds()).isAfter(now)) {
            return false;
        }
        notificationTimes.put(key, now);
        return true;
    }

    synchronized int trackedEntryCount() {
        return playerBuckets.size() + ipBuckets.size() + notificationTimes.size();
    }

    private void pruneExpired(Instant now) {
        playerBuckets.entrySet().removeIf(entry -> isExpired(entry.getValue(), now, settings.playerWindowSeconds()));
        ipBuckets.entrySet().removeIf(entry -> isExpired(entry.getValue(), now, settings.ipWindowSeconds()));
        notificationTimes.entrySet().removeIf(entry ->
                !entry.getValue().plusSeconds(settings.notifyIntervalSeconds()).isAfter(now));
    }

    private static boolean isExpired(Bucket bucket, Instant now, int windowSeconds) {
        if (bucket.lockedUntil != null) {
            return !bucket.lockedUntil.isAfter(now);
        }
        return !bucket.windowStartedAt.plusSeconds(windowSeconds).isAfter(now);
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

    private static String notificationKey(Scope scope, String playerUuid, String ip) {
        return switch (scope) {
            case PLAYER -> isBlank(playerUuid) ? null : "player:" + playerUuid;
            case IP -> isBlank(ip) ? null : "ip:" + ip;
        };
    }

    /**
     * 一次限流检查或记录失败后的总体状态
     */
    public enum Status {
        /** 允许继续验证 */
        ALLOWED,
        /** 已在锁定期内 */
        LOCKED,
        /** 本次失败刚触发限流 */
        RATE_LIMITED
    }

    /**
     * 触发限流或锁定的统计维度
     */
    public enum Scope {
        /** 按玩家 UUID 统计 */
        PLAYER,
        /** 按来源 IP 统计 */
        IP
    }

    /**
     * 返回给命令层的限流决策, 包含剩余秒数和本次是否需要写 blocked 日志
     *
     */
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

    /**
     * 单个玩家或 IP 在当前窗口内的失败计数和锁定状态
     */
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
