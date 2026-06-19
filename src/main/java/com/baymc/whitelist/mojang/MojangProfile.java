package com.baymc.whitelist.mojang;

import java.util.UUID;

/**
 * Mojang 返回的正版玩家档案
 *
 */
public record MojangProfile(UUID uuid, String name) {
    public String uuidWithoutDashes() {
        return uuid.toString().replace("-", "");
    }
}
