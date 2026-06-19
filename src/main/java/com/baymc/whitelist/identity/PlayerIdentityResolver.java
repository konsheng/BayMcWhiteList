package com.baymc.whitelist.identity;

import com.baymc.whitelist.config.PluginConfig;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves the UUID that is used as the whitelist key for each supported player identity source.
 */
public final class PlayerIdentityResolver {
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern DASHLESS_UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

    private PlayerIdentityResolver() {
    }

    public static PlayerIdentity fromPlayer(Player player, PluginConfig.UuidSource uuidSource) {
        return fromServerIdentity(player.getUniqueId(), player.getName(), uuidSource);
    }

    public static PlayerIdentity fromPreLogin(UUID serverUuid, String playerName, PluginConfig.UuidSource uuidSource) {
        return fromServerIdentity(serverUuid, playerName, uuidSource);
    }

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

    public static PlayerIdentity fromUuidInput(UUID uuid) {
        return PlayerIdentity.forUuid(uuid, uuid.toString());
    }

    public static PlayerIdentity fromOfflineName(String playerName) {
        return new PlayerIdentity(offlineNameUuid(playerName), playerName);
    }

    public static UUID offlineNameUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isValidPlayerName(String input) {
        return input != null && PLAYER_NAME_PATTERN.matcher(input).matches();
    }

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

    public static String uuidSourceLanguageKey(PluginConfig.UuidSource uuidSource) {
        return switch (uuidSource) {
            case MOJANG -> "state.uuid-source-mojang";
            case OFFLINE_NAME -> "state.uuid-source-offline-name";
            case SERVER -> "state.uuid-source-server";
        };
    }

}
