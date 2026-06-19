package com.baymc.whitelist.command.whitelist;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.security.VerifyRateLimiter;
import com.baymc.whitelist.storage.WhitelistRecord;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * /whitelist 玩家可见消息使用的占位符和状态文本格式化工具
 *
 * <p>玩家命令的输出和 /baymcwhitelist 管理命令不同: 玩家状态页需要用当前玩家身份
 * 作为记录缺失时的回退值, 安全提示还需要渲染限流作用域
 */
public final class WhitelistPlayerView {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 构造玩家自助状态查询消息所需的占位符
     *
     * <p>数据库记录为空时仍展示当前玩家身份, 数据库记录字段为空时再回退到当前玩家身份
     */
    public Map<String, String> selfStatusPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            @Nullable WhitelistRecord record
    ) {
        return Map.of(
                "player", valueOrNone(runtime, record == null ? identity.name() : firstText(record.playerName(), identity.name())),
                "uuid", valueOrNone(runtime, record == null ? identity.uuidText() : firstText(record.playerUuid(), identity.uuidText())),
                "code", valueOrNone(runtime, record == null ? null : record.code()),
                "issue_date", valueOrNone(runtime, record == null ? null : record.issueDate()),
                "used_at", format(runtime, record == null ? null : record.usedAt()),
                "source_server", valueOrNone(runtime, record == null ? null : record.sourceServer()),
                "last_seen_at", format(runtime, record == null ? null : record.lastSeenAt())
        );
    }

    /**
     * 构造限流通知给后台和在线管理者时使用的占位符
     */
    public Map<String, String> securityPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            String ip,
            VerifyRateLimiter.Decision decision
    ) {
        return Map.of(
                "player", valueOrNone(runtime, identity.name()),
                "uuid", valueOrNone(runtime, identity.uuidText()),
                "scope", runtime.lang().plain(scopeLanguageKey(decision.scope())),
                "ip", valueOrNone(runtime, ip),
                "remaining_seconds", String.valueOf(decision.remainingSeconds())
        );
    }

    /**
     * 构造玩家被限流或锁定时收到的反馈占位符
     */
    public Map<String, String> securityFeedbackPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            VerifyRateLimiter.Decision decision
    ) {
        return Map.of(
                "remaining_seconds", String.valueOf(decision.remainingSeconds()),
                "scope", runtime.lang().plain(scopeLanguageKey(decision.scope()))
        );
    }

    /**
     * 根据限流作用域返回审计日志中使用的短消息
     */
    public String scopeMessage(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "ip" : "player";
    }

    /**
     * 根据限流作用域返回语言文件中的作用域标签键
     */
    public String scopeLanguageKey(VerifyRateLimiter.Scope scope) {
        return scope == VerifyRateLimiter.Scope.IP ? "security.scope-ip" : "security.scope-player";
    }

    private String valueOrNone(BayMcWhiteListPlugin.RuntimeState runtime, Object value) {
        if (value == null) {
            return runtime.lang().plain("state.none");
        }
        String text = String.valueOf(value);
        return text.isBlank() ? runtime.lang().plain("state.none") : text;
    }

    private String format(BayMcWhiteListPlugin.RuntimeState runtime, LocalDateTime dateTime) {
        return dateTime == null ? runtime.lang().plain("state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
