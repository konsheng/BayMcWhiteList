package com.baymc.whitelist.command.whitelist;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.storage.WhitelistLogEntry;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * /whitelist 验证流程使用的审计日志写入器
 *
 * <p>失败尝试日志使用静默写入, 避免一次审计失败阻断玩家继续收到验证反馈;
 * 成功日志仍向外抛出 SQLException, 保持原先数据库操作失败时反馈 operation-failed 的行为
 */
public final class WhitelistAttemptLogger {
    private final Logger logger;

    public WhitelistAttemptLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * 写入验证成功审计日志, 失败时交给调用方统一处理数据库错误
     */
    public void logSuccess(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String code,
            String ip,
            LocalDateTime usedAt
    ) throws SQLException {
        runtime.repository().log(new WhitelistLogEntry(
                identity.uuidText(),
                identity.name(),
                "VERIFY_SUCCESS",
                code,
                runtime.config().server().name(),
                ip,
                null,
                usedAt
        ));
    }

    /**
     * 尝试写入验证过程审计日志, 写入失败只记录后台警告
     */
    public void logQuietly(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String code,
            String action,
            String message,
            String ip
    ) {
        try {
            runtime.repository().log(new WhitelistLogEntry(
                    identity.uuidText(),
                    identity.name(),
                    action,
                    code,
                    runtime.config().server().name(),
                    ip,
                    message,
                    now(runtime)
            ));
        } catch (SQLException exception) {
            logger.warning("Failed to write whitelist attempt log: " + exception.getMessage());
        }
    }

    /**
     * 使用当前配置时区生成审计时间
     */
    public LocalDateTime now(BayMcWhiteListPlugin.RuntimeState runtime) {
        return LocalDateTime.now(runtime.config().code().zoneId());
    }
}
