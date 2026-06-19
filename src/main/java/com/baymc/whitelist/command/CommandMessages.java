package com.baymc.whitelist.command;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.code.GeneratedCode;
import com.baymc.whitelist.command.target.AddTarget;
import com.baymc.whitelist.command.target.LookupTarget;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.identity.PlayerIdentityResolver;
import com.baymc.whitelist.storage.WhitelistRecord;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * /baymcwhitelist 命令共用的语言状态和占位符格式化工具
 *
 * <p>子命令只表达业务流程, 所有可见文本对应的占位符名称和空值渲染
 * 都集中在这里, 方便和 lang/*.yml 保持一致
 */
public final class CommandMessages {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 把数据库空值渲染成语言文件中的"无"状态文本
     */
    public String value(BayMcWhiteListPlugin.RuntimeState runtime, Object value) {
        return value == null ? state(runtime, "state.none") : String.valueOf(value);
    }

    /**
     * 统一格式化命令输出中的数据库时间戳
     */
    public String format(BayMcWhiteListPlugin.RuntimeState runtime, LocalDateTime dateTime) {
        return dateTime == null ? state(runtime, "state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    /**
     * 从语言文件读取用于嵌入占位符的纯文本状态标签
     */
    public String state(BayMcWhiteListPlugin.RuntimeState runtime, String key) {
        return runtime.lang().plain(key);
    }

    /**
     * 根据当前 UUID 来源返回对应语言状态键
     */
    public String uuidSourceStateKey(BayMcWhiteListPlugin.RuntimeState runtime) {
        return PlayerIdentityResolver.uuidSourceLanguageKey(runtime.config().player().uuidSource());
    }

    /**
     * 构造手动添加白名单命令使用的身份占位符
     */
    public Map<String, String> addPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            AddTarget target
    ) {
        return identityPlaceholders(runtime, target.identity());
    }

    /**
     * 构造邀请码生成成功消息所需的玩家身份和过期时间占位符
     */
    public Map<String, String> generatedCodePlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity,
            GeneratedCode generatedCode
    ) {
        return Map.of(
                "player", identity.name(),
                "uuid", identity.uuidText(),
                "uuid_source", state(runtime, uuidSourceStateKey(runtime)),
                "code", generatedCode.code(),
                "expire_time", DATE_TIME_FORMATTER.format(generatedCode.expiresAt())
        );
    }

    /**
     * 构造只需要玩家名和 UUID 的通用身份占位符
     */
    public Map<String, String> identityPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity
    ) {
        return Map.of(
                "player", identity.name(),
                "uuid", identity.uuidText()
        );
    }

    /**
     * 构造移除白名单后反馈和踢出提示共用的占位符
     */
    public Map<String, String> removalPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            WhitelistRecord record
    ) {
        return Map.of(
                "player", value(runtime, record.playerName()),
                "uuid", value(runtime, record.playerUuid()),
                "server_mode", state(
                        runtime,
                        runtime.config().server().mode() == PluginConfig.ServerMode.LOGIN
                                ? "state.mode-login"
                                : "state.mode-protected"
                )
        );
    }

    /**
     * 根据输入文本判断状态查询是 UUID 查询还是名称查询
     */
    public String statusLookupType(
            BayMcWhiteListPlugin.RuntimeState runtime,
            LookupTarget target
    ) {
        return state(
                runtime,
                PlayerIdentityResolver.parseUuid(target.input()).isPresent() ? "state.lookup-uuid" : "state.lookup-name"
        );
    }

    /**
     * 构造状态查询未命中时展示输入和查询类型的占位符
     */
    public Map<String, String> statusLookupPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            LookupTarget target
    ) {
        return Map.of(
                "player", value(runtime, target.input()),
                "lookup_input", value(runtime, target.input()),
                "lookup_type", statusLookupType(runtime, target)
        );
    }
}
