package com.baymc.whitelist.listener;

import com.baymc.whitelist.BayMcWhiteListPlugin;
import com.baymc.whitelist.config.PluginConfig;
import com.baymc.whitelist.identity.PlayerIdentity;
import com.baymc.whitelist.storage.WhitelistLogEntry;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 在玩家加入受保护服务器前强制检查 MySQL 白名单状态
 */
public final class WhitelistLoginListener implements Listener {
    private final BayMcWhiteListPlugin plugin;

    /**
     * 保存预登录检查过程中使用的插件门面对象
     */
    public WhitelistLoginListener(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 在异步预登录阶段拒绝受保护服务器上的未过白玩家
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try (BayMcWhiteListPlugin.RuntimeState runtime = plugin.runtimeState()) {
            if (runtime.config().server().mode() != PluginConfig.ServerMode.PROTECTED) {
                return;
            }
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                return;
            }

            if (!runtime.databaseReady()) {
                disallow(runtime, event, "join.database-unavailable");
                return;
            }

            // AsyncPlayerPreLoginEvent 本身已在主线程/区域线程之外运行
            // 因此可以在这里完成白名单查询, 再把登录结果返回给 Paper/Folia
            PlayerIdentity identity = PlayerIdentity.fromPreLogin(
                    event.getUniqueId(),
                    event.getName()
            );

            try {
                if (runtime.repository().isWhitelisted(identity.uuidText())) {
                    runtime.repository().updateLastSeen(identity.uuidText(), now(runtime));
                    return;
                }

                runtime.repository().log(new WhitelistLogEntry(
                        identity.uuidText(),
                        identity.name(),
                        "JOIN_DENIED_NOT_WHITELISTED",
                        null,
                        runtime.config().server().name(),
                        addressOf(event.getAddress()),
                        null,
                        now(runtime)
                ));
                disallow(runtime, event, "join.not-whitelisted");
            } catch (SQLException exception) {
                plugin.getLogger().severe("Failed to check whitelist status for " + event.getName() + ".");
                exception.printStackTrace();
                disallow(runtime, event, "join.database-unavailable");
            }
        }
    }

    /**
     * 构建配置中的踢出消息, 并拒绝本次登录
     */
    private void disallow(BayMcWhiteListPlugin.RuntimeState runtime, AsyncPlayerPreLoginEvent event, String languageKey) {
        Component message = runtime.lang().joined(languageKey, Map.of(
                "player", event.getName(),
                "code_prefix", runtime.config().code().prefix()
        ));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, message);
    }

    /**
     * 审计日志和最后出现时间都使用配置中的时区
     */
    private static LocalDateTime now(BayMcWhiteListPlugin.RuntimeState runtime) {
        return LocalDateTime.now(runtime.config().code().zoneId());
    }

    /**
     * 将 InetAddress 转为审计日志可写入的可空文本
     */
    private static String addressOf(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }
}
