package com.baymc.whitelist.command.whitelist;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.VerificationResult;
import com.baymc.whitelist.command.CommandContext;
import com.baymc.whitelist.command.CommandExecution;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.security.VerifyRateLimiter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.logging.Level;

/**
 * 处理 /whitelist <邀请码> 的验证主流程
 *
 * <p>该动作负责白名单状态判断, 邀请码校验, 成功写入, 限流检查和结果反馈;
 * 安全通知与审计日志细节交给专门组件处理
 */
public final class WhitelistVerifyAction {
    private final WhitelistAttemptLogger attemptLogger;
    private final WhitelistSecurityFeedback securityFeedback;

    public WhitelistVerifyAction(
            WhitelistAttemptLogger attemptLogger,
            WhitelistSecurityFeedback securityFeedback
    ) {
        this.attemptLogger = attemptLogger;
        this.securityFeedback = securityFeedback;
    }

    /**
     * 异步执行邀请码验证, 并在任务结束后释放运行期快照
     */
    public CommandExecution execute(
            CommandContext context,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip
    ) {
        return context.runAsyncClosing(() -> verifyWithSecurity(context, player, identity, rawCode, ip));
    }

    private void verifyWithSecurity(
            CommandContext context,
            Player player,
            PlayerIdentity identity,
            String rawCode,
            String ip
    ) {
        BayMcWhiteListPlugin.RuntimeState runtime = context.runtime();
        try {
            if (runtime.repository().isWhitelisted(identity.uuidText())) {
                attemptLogger.logQuietly(runtime, identity, null, "VERIFY_ALREADY_WHITELISTED", "already_whitelisted", ip);
                runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.already-whitelisted"));
                return;
            }

            VerifyRateLimiter.Decision locked = runtime.verifyRateLimiter().check(identity.uuidText(), ip);
            if (locked.status() == VerifyRateLimiter.Status.LOCKED) {
                securityFeedback.handleLockedAttempt(runtime, player, identity, rawCode, ip, locked);
                return;
            }

            VerificationResult result = runtime.inviteCodeService().verify(rawCode, identity.uuidText());
            if (result.status() == VerificationResult.Status.INVALID_FORMAT) {
                securityFeedback.handleInvalidCode(
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
                securityFeedback.handleInvalidCode(
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

            LocalDateTime usedAt = attemptLogger.now(runtime);
            runtime.repository().upsert(identity, result.normalizedCode(), result.issueDate(), usedAt);
            attemptLogger.logSuccess(runtime, identity, result.normalizedCode(), ip, usedAt);
            runtime.verifyRateLimiter().reset(identity.uuidText(), ip);

            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "code.success"));
        } catch (SQLException exception) {
            context.logger().log(Level.SEVERE, "Failed to verify whitelist code for " + identity.name() + ".", exception);
            runtime.scheduler().runForPlayer(player, () -> runtime.lang().send(player, "database.operation-failed"));
        }
    }
}
