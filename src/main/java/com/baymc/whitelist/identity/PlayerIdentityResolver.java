package com.baymc.whitelist.identity;

import com.baymc.whitelist.config.PluginConfig;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 按配置的 UUID 来源解析白名单使用的标准玩家身份
 */
public final class PlayerIdentityResolver {
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern DASHLESS_UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

    private PlayerIdentityResolver() {
    }

    /**
     * 从在线玩家实体解析当前服务器看到的玩家身份
     */
    public static PlayerIdentity fromPlayer(Player player, PluginConfig.UuidSource uuidSource) {
        return fromServerIdentity(player.getUniqueId(), player.getName(), uuidSource);
    }

    /**
     * 在 Player 对象创建前, 根据预登录事件中的 UUID 和玩家名解析身份
     */
    public static PlayerIdentity fromPreLogin(UUID serverUuid, String playerName, PluginConfig.UuidSource uuidSource) {
        return fromServerIdentity(serverUuid, playerName, uuidSource);
    }

    /**
     * 根据配置决定信任服务端 UUID, 还是按离线名算法重新计算 UUID
     */
    public static PlayerIdentity fromServerIdentity(
            UUID serverUuid,
            String playerName,
            PluginConfig.UuidSource uuidSource
    ) {
        return switch (uuidSource) {
            case MOJANG, SERVER -> new PlayerIdentity(serverUuid, playerName);
            case OFFLINE_NAME -> new PlayerIdentity(offlineNameUuid(playerName), playerName);
        };
    }

    /**
     * 管理员直接输入 UUID 时, 使用 UUID 本身作为展示名兜底
     */
    public static PlayerIdentity fromUuidInput(UUID uuid) {
        return PlayerIdentity.forUuid(uuid, uuid.toString());
    }

    /**
     * 按 Bukkit 离线名算法为合法玩家名创建身份
     */
    public static PlayerIdentity fromOfflineName(String playerName) {
        return new PlayerIdentity(offlineNameUuid(playerName), playerName);
    }

    /**
     * 复现 Bukkit 离线模式 OfflinePlayer:<name> 的 UUID 计算方式
     */
    public static UUID offlineNameUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验管理员输入是否符合 Minecraft 玩家名边界
     */
    public static boolean isValidPlayerName(String input) {
        return input != null && PLAYER_NAME_PATTERN.matcher(input).matches();
    }

    /**
     * 接受标准 UUID 或无横杠 32 位 UUID, 解析失败时返回空结果
     */
    public static Optional<UUID> parseUuid(String input) {
        if (input == null) {
            return Optional.empty();
        }
        try {
            String normalized = input.trim().replace("-", "");
            if (DASHLESS_UUID_PATTERN.matcher(normalized).matches()) {
                return Optional.of(UUID.fromString(normalized.replaceFirst(
                        "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                        "$1-$2-$3-$4-$5"
                )));
            }
            return Optional.of(UUID.fromString(input.trim()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 返回当前 UUID 来源对应的语言文件状态键
     */
    public static String uuidSourceLanguageKey(PluginConfig.UuidSource uuidSource) {
        return switch (uuidSource) {
            case MOJANG -> "state.uuid-source-mojang";
            case OFFLINE_NAME -> "state.uuid-source-offline-name";
            case SERVER -> "state.uuid-source-server";
        };
    }

}
