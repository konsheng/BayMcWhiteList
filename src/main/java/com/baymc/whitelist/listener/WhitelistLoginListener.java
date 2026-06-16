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

public final class WhitelistLoginListener implements Listener {
    private final BayMcWhiteListPlugin plugin;

    public WhitelistLoginListener(BayMcWhiteListPlugin plugin) {
        this.plugin = plugin;
    }

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

        // AsyncPlayerPreLoginEvent already runs off the main/region thread, so
        // the whitelist lookup can be completed here before the login decision
        // is returned to Paper/Folia.
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

    private void disallow(AsyncPlayerPreLoginEvent event, String languageKey) {
        Component message = plugin.lang().joined(languageKey, Map.of(
                "player", event.getName(),
                "prefix", plugin.pluginConfig().code().prefix()
        ));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, message);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(plugin.pluginConfig().code().zoneId());
    }

    private static String addressOf(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }
}
