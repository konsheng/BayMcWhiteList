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
        if (plugin.pluginConfig().server().mode() != PluginConfig.ServerMode.PROTECTED) {
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        if (!plugin.isDatabaseReady()) {
            disallow(event, "join.database-unavailable");
            return;
        }

        // AsyncPlayerPreLoginEvent 本身已在主线程/区域线程之外运行
        // 因此可以在这里完成白名单查询, 再把登录结果返回给 Paper/Folia
        PlayerIdentity identity = PlayerIdentity.fromPreLogin(
                event.getUniqueId(),
                event.getName(),
                plugin.pluginConfig().player().idType()
        );

        try {
            if (plugin.repository().isWhitelisted(identity.key())) {
                plugin.repository().updateLastSeen(identity.key(), now());
                return;
            }

            plugin.repository().log(new WhitelistLogEntry(
                    identity.key(),
                    identity.name(),
                    "JOIN_DENIED_NOT_WHITELISTED",
                    null,
                    plugin.pluginConfig().server().name(),
                    addressOf(event.getAddress()),
                    null,
                    now()
            ));
            disallow(event, "join.not-whitelisted");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Failed to check whitelist status for " + event.getName() + ".");
            exception.printStackTrace();
            disallow(event, "join.database-unavailable");
        }
    }

    /**
     * 构建配置中的踢出消息, 并拒绝本次登录
     */
    private void disallow(AsyncPlayerPreLoginEvent event, String languageKey) {
        Component message = plugin.lang().joined(languageKey, Map.of(
                "player", event.getName(),
                "code_prefix", plugin.pluginConfig().code().prefix()
        ));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, message);
    }

    /**
     * 审计日志和最后出现时间都使用配置中的时区
     */
    private LocalDateTime now() {
        return LocalDateTime.now(plugin.pluginConfig().code().zoneId());
    }

    /**
     * 将 InetAddress 转为审计日志可写入的可空文本
     */
    private static String addressOf(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }
}
