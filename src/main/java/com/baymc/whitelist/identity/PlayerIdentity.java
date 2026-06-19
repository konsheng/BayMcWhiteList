package com.baymc.whitelist.identity;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 用于签名, 存储和登录检查的标准化玩家身份
 *
 */
public record PlayerIdentity(UUID uuid, String name) {
    public static PlayerIdentity fromPlayer(Player player) {
        return new PlayerIdentity(player.getUniqueId(), player.getName());
    }

    public static PlayerIdentity fromPreLogin(UUID uuid, String name) {
        return new PlayerIdentity(uuid, name);
    }

    public static PlayerIdentity forUuid(UUID uuid, String displayName) {
        return new PlayerIdentity(uuid, displayName);
    }

    public String uuidText() {
        return uuid.toString();
    }
}
