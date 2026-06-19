package com.baymc.whitelist.identity;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 用于签名, 存储和登录检查的标准化玩家身份
 *
 * @param uuid 用于签名, 存储和登录检查的玩家 UUID
 * @param name 用于管理员查看和存储的玩家名
 */
public record PlayerIdentity(UUID uuid, String name) {
    /**
     * 从登录服中的在线 Player 创建身份
     */
    public static PlayerIdentity fromPlayer(Player player) {
        return new PlayerIdentity(player.getUniqueId(), player.getName());
    }

    /**
     * 在 Player 对象创建前, 根据 AsyncPlayerPreLoginEvent 数据创建身份
     */
    public static PlayerIdentity fromPreLogin(UUID uuid, String name) {
        return new PlayerIdentity(uuid, name);
    }

    /**
     * 当管理员直接提供 UUID 时创建身份
     */
    public static PlayerIdentity forUuid(UUID uuid, String displayName) {
        return new PlayerIdentity(uuid, displayName);
    }

    /**
     * 返回数据库查询和邀请码签名使用的标准 UUID 文本
     */
    public String uuidText() {
        return uuid.toString();
    }
}
