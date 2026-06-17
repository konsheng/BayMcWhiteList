package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.VerificationResult;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.security.VerifyRateLimiter;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import com.baymc.whitelist.storage.WhitelistRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles the player-facing /whitelist invite-code command.
 */
public final class WhitelistCommand implements TabExecutor {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BayMcWhiteListPlugin plugin;

    public WhitelistCommand(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState();
        boolean runtimeOwnedByAsync = false;
        try {
            Player player = sender instanceof Player playerSender ? playerSender : null;
            CommandBoundaries.WhitelistDecision decision = CommandBoundaries.whitelistDecision(
                    player != null,
                    player != null && player.hasPermission("baymcwhitelist.use"),
                    player != null && player.hasPermission("baymcwhitelist.status.self"),
                    runtime.config().server().mode(),
                    args.length
            );
            switch (decision) {
                case ONLY_PLAYER -> runtime.lang().send(sender, "common.only-player");
                case NO_PERMISSION -> runtime.lang().send(sender, "common.no-permission");
                case LOGIN_SERVER_ONLY -> runtime.lang().send(sender, "code.login-server-only");
                case USAGE -> runtime.lang().send(sender, "usage.whitelist");
                case STATUS, VERIFY -> {
                }
            }
            if (decision != CommandBoundaries.WhitelistDecision.STATUS
                    && decision != CommandBoundaries.WhitelistDecision.VERIFY) {
                return true;
            }

            PlayerIdentity identity = PlayerIdentity.fromPlayer(player, runtime.config().player().idType());
            if (!runtime.databaseReady()) {
                runtime.lang().send(player, "mysql.not-ready");
                return true;
            }

            if (decision == CommandBoundaries.WhitelistDecision.STATUS) {
                runtime.scheduler().runAsync(() -> {
                    try {
                        sendSelfStatus(runtime, player, identity);
                    } finally {
                        runtime.close();
                    }
                });
                runtimeOwnedByAsync = true;
                return true;
            }

            String ip = addressOf(player);
            String rawCode = args[0];
            runtime.scheduler().runAsync(() -> {
                try {
                    verifyWithSecurity(runtime, player, identity, rawCode, ip);
                } finally {
                    runtime.close();
                }
            });
            runtimeOwnedByAsync = true;
            return true;
        } finally {
            if (!runtimeOwnedByAsync) {
                runtime.close();
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return List.of();
    }

    private void sendSelfStatus(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity
    ) {
        try {
            Optional<WhitelistRecord> record = runtime.repository().findByKey(identity.key());
            runtime.scheduler().runForPlayer(player, () -> {
                if (record.isPresent()) {
                    runtime.lang().send(player, "player.status-whitelisted",
                            selfStatusPlaceholders(runtime, identity, record.orElseThrow()));
                    return;
                }
                runtime.lang().send(player, "player.status-not-whitelisted",
                        selfStatusPlaceholders(runtime, identity, null));
            });
        } catch (SQLException exception) {
            plugin.getLogger().severe("Failed to query whitelist self status for " + identity.name() + ".");
            exception.printStackTrace();
            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "mysql.operation-failed"));
        }
    }

    /**
     * 构建玩家自助状态查询占位符
     *
     * <p>自助查询只展示当前玩家自己的记录; 如果数据库记录缺少 UUID, 会回退到当前在线玩家实体的 UUID
     */
    private Map<String, String> selfStatusPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            @Nullable WhitelistRecord record
    ) {
        return Map.of(
                "player", valueOrNone(runtime, record == null ? identity.name() : firstText(record.playerName(), identity.name())),
                "player_key", valueOrNone(runtime, identity.key()),
                "uuid", valueOrNone(runtime, record == null ? identity.uuid() : firstText(record.playerUuid(), String.valueOf(identity.uuid()))),
                "code", valueOrNone(runtime, record == null ? null : record.code()),
                "issue_date", valueOrNone(runtime, record == null ? null : record.issueDate()),
                "used_at", format(runtime, record == null ? null : record.usedAt()),
                "source_server", valueOrNone(runtime, record == null ? null : record.sourceServer()),
                "last_seen_at", format(runtime, record == null ? null : record.lastSeenAt())
        );
    }

    private void verifyWithSecurity(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip
    ) {
        try {
            if (runtime.repository().isWhitelisted(identity.key())) {
                logAttemptQuietly(runtime, identity, null, "VERIFY_ALREADY_WHITELISTED", "already_whitelisted", ip);
                runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.already-whitelisted"));
                return;
            }

            VerifyRateLimiter.Decision locked = runtime.verifyRateLimiter().check(identity.key(), ip);
            if (locked.status() == VerifyRateLimiter.Status.LOCKED) {
                handleLockedAttempt(runtime, player, identity, rawCode, ip, locked);
                return;
            }

            VerificationResult result = runtime.inviteCodeService().verify(rawCode, identity.key());
            if (result.status() == VerificationResult.Status.INVALID_FORMAT) {
                handleInvalidCode(
                        runtime,
                        player,
                        identity,
                        rawCode,
                        ip,
                        "VERIFY_INVALID_FORMAT",
                        "invalid_format",
                        "code.invalid-format",
                        Map.of("code_prefix", runtime.config().code().prefix())
                );
                return;
            }
            if (result.status() == VerificationResult.Status.INVALID_OR_EXPIRED) {
                handleInvalidCode(
                        runtime,
                        player,
                        identity,
                        rawCode,
                        ip,
                        "VERIFY_INVALID_OR_EXPIRED",
                        "invalid_or_expired",
                        "code.invalid-or-expired",
                        Map.of()
                );
                return;
            }

            LocalDateTime usedAt = now(runtime);
            runtime.repository().upsert(identity, result.normalizedCode(), result.issueDate(), usedAt);
            runtime.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    "VERIFY_SUCCESS",
                    result.normalizedCode(),
                    runtime.config().server().name(),
                    ip,
                    null,
                    usedAt
            ));
            runtime.verifyRateLimiter().reset(identity.key(), ip);

            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.success"));
        } catch (SQLException exception) {
            plugin.getLogger().severe("Failed to verify whitelist code for " + identity.name() + ".");
            exception.printStackTrace();
            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "mysql.operation-failed"));
        }
    }

    private void handleInvalidCode(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip,
            String invalidAction,
            String invalidMessage,
            String invalidLanguageKey,
            Map<String, String> invalidPlaceholders
    ) {
        logAttemptQuietly(runtime, identity, rawCode, invalidAction, invalidMessage, ip);
        VerifyRateLimiter.Decision limited = runtime.verifyRateLimiter().recordFailure(identity.key(), ip);
        if (limited.status() == VerifyRateLimiter.Status.RATE_LIMITED) {
            logAttemptQuietly(runtime, identity, rawCode, rateLimitedAction(limited.scope()), scopeMessage(limited.scope()), ip);
            notifyRateLimited(runtime, identity, ip, limited);
            sendSecurityFeedback(runtime, player, limited, true);
            return;
        }

        runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, invalidLanguageKey, invalidPlaceholders));
    }

    private void handleLockedAttempt(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip,
            VerifyRateLimiter.Decision locked
    ) {
        if (locked.shouldLogBlocked()) {
            logAttemptQuietly(runtime, identity, rawCode, "VERIFY_RATE_LIMIT_BLOCKED", scopeMessage(locked.scope()), ip);
        }
        sendSecurityFeedback(runtime, player, locked, false);
    }

    private void sendSecurityFeedback(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            VerifyRateLimiter.Decision decision,
            boolean newlyLimited
    ) {
        Map<String, String> placeholders = Map.of(
                "remaining_seconds", String.valueOf(decision.remainingSeconds()),
                "scope", runtime.lang().plain(scopeLanguageKey(decision.scope()))
        );
        boolean kick = runtime.verifyRateLimiter().settings().kickOnLock();
        String messageKey = newlyLimited ? "security.verify-rate-limited" : "security.verify-locked";
        String kickKey = newlyLimited ? "security.verify-rate-limited-kick" : "security.verify-locked-kick";
        runtime.scheduler().runForPlayer(player, () -> {
            if (kick) {
                Component message = runtime.lang().joined(kickKey, placeholders);
                player.kick(message);
                return;
            }
            runtime.lang().send(player, messageKey, placeholders);
        });
    }

    private void notifyRateLimited(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String ip,
            VerifyRateLimiter.Decision decision
    ) {
        PluginConfig.VerifyRateLimitSettings settings = runtime.verifyRateLimiter().settings();
        if (!settings.notifyConsole() && !settings.notifyAdmins()) {
            return;
        }
        if (!runtime.verifyRateLimiter().shouldNotify(decision.scope(), identity.key(), ip)) {
            return;
        }

        Map<String, String> placeholders = securityPlaceholders(runtime, identity, ip, decision);
        runtime.scheduler().runGlobal(() -> {
            if (settings.notifyConsole()) {
                runtime.lang().send(Bukkit.getConsoleSender(), "security.notify-rate-limited", placeholders);
            }
            if (!settings.notifyAdmins()) {
                return;
            }

            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission(settings.notifyPermission())) {
                    runtime.scheduler().runForPlayer(admin, () ->
                            runtime.lang().send(admin, "security.notify-rate-limited", placeholders));
                }
            }
        });
    }

    private Map<String, String> securityPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String ip,
            VerifyRateLimiter.Decision decision
    ) {
        return Map.of(
                "player", valueOrNone(runtime, identity.name()),
                "player_key", valueOrNone(runtime, identity.key()),
                "scope", runtime.lang().plain(scopeLanguageKey(decision.scope())),
                "ip", valueOrNone(runtime, ip),
                "remaining_seconds", String.valueOf(decision.remainingSeconds())
        );
    }

    private void logAttemptQuietly(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String code,
            String action,
            String message,
            String ip
    ) {
        try {
            runtime.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    action,
                    code,
                    runtime.config().server().name(),
                    ip,
                    message,
                    now(runtime)
            ));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to write whitelist attempt log: " + exception.getMessage());
        }
    }

    private static String rateLimitedAction(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "VERIFY_RATE_LIMITED_IP" : "VERIFY_RATE_LIMITED_PLAYER";
    }

    private static String scopeMessage(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "ip" : "player";
    }

    private static String scopeLanguageKey(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "security.scope-ip" : "security.scope-player";
    }

    private static String valueOrNone(BayMcWhiteListPlugin.RuntimeState runtime, Object value) {
        if (value == null) {
            return runtime.lang().plain("state.none");
        }
        String text = String.valueOf(value);
        return text.isBlank() ? runtime.lang().plain("state.none") : text;
    }

    private static String format(BayMcWhiteListPlugin.RuntimeState runtime, LocalDateTime dateTime) {
        return dateTime == null ? runtime.lang().plain("state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    private static String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static LocalDateTime now(BayMcWhiteListPlugin.RuntimeState runtime) {
        return LocalDateTime.now(runtime.config().code().zoneId());
    }

    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }
}
