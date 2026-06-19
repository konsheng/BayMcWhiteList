package com.baymc.whitelist.mojang;

import java.util.UUID;

/**
 * Mojang 返回的正版玩家档案
 *
 * @param uuid 正版 Minecraft UUID
 * @param name Mojang 返回的规范玩家名
 */
public record MojangProfile(UUID uuid, String name) {
    /**
     * 返回 Mojang API 使用的无横杠 UUID
     *
     * @return 32 位无横杠 UUID 文本
     */
    public String uuidWithoutDashes() {
        return uuid.toString().replace("-", "");
    }
}
