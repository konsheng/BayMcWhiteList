package com.baymc.whitelist.identity;

import com.baymc.whitelist.config.PluginConfig;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public record PlayerIdentity(String key, UUID uuid, String name) {
    public static PlayerIdentity fromPlayer(Player player, PluginConfig.PlayerIdType idType) {
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        return new PlayerIdentity(keyFor(idType, uuid, name), uuid, name);
    }

    public static PlayerIdentity fromPreLogin(UUID uuid, String name, PluginConfig.PlayerIdType idType) {
        return new PlayerIdentity(keyFor(idType, uuid, name), uuid, name);
    }

    public static PlayerIdentity forName(String name) {
        return new PlayerIdentity(name.toLowerCase(Locale.ROOT), null, name);
    }

    public static PlayerIdentity forUuid(UUID uuid, String displayName) {
        return new PlayerIdentity(uuid.toString(), uuid, displayName);
    }

    public static String keyFor(PluginConfig.PlayerIdType idType, UUID uuid, String name) {
        return switch (idType) {
            case UUID -> uuid.toString();
            case NAME -> name.toLowerCase(Locale.ROOT);
        };
    }
}
