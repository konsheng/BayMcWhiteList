package com.baymc.whitelist.identity;

import com.baymc.whitelist.config.PluginConfig;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/**
 * 用于签名, 存储和登录检查的标准化玩家身份
 *
 * @param key 根据配置选择的标准白名单键
 * @param uuid 从 Bukkit/Paper 获取到的 UUID, 可能为空
 * @param name 用于管理员查看和存储的玩家名
 */
public record PlayerIdentity(String key, UUID uuid, String name) {
    /**
     * 从登录服中的在线 Player 创建身份
     */
    public static PlayerIdentity fromPlayer(Player player, PluginConfig.PlayerIdType idType) {
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        return new PlayerIdentity(keyFor(idType, uuid, name), uuid, name);
    }

    /**
     * 在 Player 对象创建前, 根据 AsyncPlayerPreLoginEvent 数据创建身份
     */
    public static PlayerIdentity fromPreLogin(UUID uuid, String name, PluginConfig.PlayerIdType idType) {
        return new PlayerIdentity(keyFor(idType, uuid, name), uuid, name);
    }

    /**
     * 为管理员生成或查询操作创建 name 模式身份
     */
    public static PlayerIdentity forName(String name) {
        return new PlayerIdentity(name.toLowerCase(Locale.ROOT), null, name);
    }

    /**
     * 当管理员直接提供 UUID 时创建 UUID 模式身份
     */
    public static PlayerIdentity forUuid(UUID uuid, String displayName) {
        return new PlayerIdentity(uuid.toString(), uuid, displayName);
    }

    /**
     * 根据配置的身份策略生成标准白名单键
     */
    public static String keyFor(PluginConfig.PlayerIdType idType, UUID uuid, String name) {
        return switch (idType) {
            case UUID -> uuid.toString();
            case NAME -> name.toLowerCase(Locale.ROOT);
        };
    }
}
