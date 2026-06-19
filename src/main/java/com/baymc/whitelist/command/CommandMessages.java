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

    public String value(BayMcWhiteListPlugin.RuntimeState runtime, Object value) {
        return value == null ? state(runtime, "state.none") : String.valueOf(value);
    }

    public String format(BayMcWhiteListPlugin.RuntimeState runtime, LocalDateTime dateTime) {
        return dateTime == null ? state(runtime, "state.none") : DATE_TIME_FORMATTER.format(dateTime);
    }

    public String state(BayMcWhiteListPlugin.RuntimeState runtime, String key) {
        return runtime.lang().plain(key);
    }

    public String uuidSourceStateKey(BayMcWhiteListPlugin.RuntimeState runtime) {
        return PlayerIdentityResolver.uuidSourceLanguageKey(runtime.config().player().uuidSource());
    }

    public Map<String, String> addPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            AddTarget target
    ) {
        return identityPlaceholders(runtime, target.identity());
    }

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

    public Map<String, String> identityPlaceholders(
            BayMcWhiteListPlugin.RuntimeState runtime,
            PlayerIdentity identity
    ) {
        return Map.of(
                "player", identity.name(),
                "uuid", identity.uuidText()
        );
    }

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

    public String statusLookupType(
            BayMcWhiteListPlugin.RuntimeState runtime,
            LookupTarget target
    ) {
        return state(
                runtime,
                PlayerIdentityResolver.parseUuid(target.input()).isPresent() ? "state.lookup-uuid" : "state.lookup-name"
        );
    }

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
