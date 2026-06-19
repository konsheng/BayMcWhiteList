package com.baymc.whitelist.command.whitelist;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.security.VerifyRateLimiter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * /whitelist 邀请码验证失败后的限流反馈和安全通知流程
 *
 * <p>这里集中处理玩家锁定提示, 可选踢出, 控制台通知和在线管理者通知,
 * 让验证码主流程只表达验证成功或失败的业务分支
 */
public final class WhitelistSecurityFeedback {
    private final WhitelistPlayerView view;
    private final WhitelistAttemptLogger attemptLogger;

    public WhitelistSecurityFeedback(
            WhitelistPlayerView view,
            WhitelistAttemptLogger attemptLogger
    ) {
        this.view = view;
        this.attemptLogger = attemptLogger;
    }

    /**
     * 记录一次无效邀请码尝试, 并在达到阈值时进入限流反馈流程
     */
    public void handleInvalidCode(
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
        attemptLogger.logQuietly(runtime, identity, rawCode, invalidAction, invalidMessage, ip);
        VerifyRateLimiter.Decision limited = runtime.verifyRateLimiter().recordFailure(identity.uuidText(), ip);
        if (limited.status() == VerifyRateLimiter.Status.RATE_LIMITED) {
            attemptLogger.logQuietly(runtime, identity, rawCode, rateLimitedAction(limited.scope()), view.scopeMessage(limited.scope()), ip);
            notifyRateLimited(runtime, identity, ip, limited);
            sendSecurityFeedback(runtime, player, limited, true);
            return;
        }

        runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, invalidLanguageKey, invalidPlaceholders));
    }

    /**
     * 处理已经处于锁定状态的重复验证尝试
     */
    public void handleLockedAttempt(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip,
            VerifyRateLimiter.Decision locked
    ) {
        if (locked.shouldLogBlocked()) {
            attemptLogger.logQuietly(runtime, identity, rawCode, "VERIFY_RATE_LIMIT_BLOCKED", view.scopeMessage(locked.scope()), ip);
        }
        sendSecurityFeedback(runtime, player, locked, false);
    }

    private void sendSecurityFeedback(
            BayMcWhiteListPlugin.RuntimeState runtime,
            Player player,
            VerifyRateLimiter.Decision decision,
            boolean newlyLimited
    ) {
        Map<String, String> placeholders = view.securityFeedbackPlaceholders(runtime, decision);
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
        if (!runtime.verifyRateLimiter().shouldNotify(decision.scope(), identity.uuidText(), ip)) {
            return;
        }

        Map<String, String> placeholders = view.securityPlaceholders(runtime, identity, ip, decision);
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

    private String rateLimitedAction(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "VERIFY_RATE_LIMITED_IP" : "VERIFY_RATE_LIMITED_PLAYER";
    }
}
