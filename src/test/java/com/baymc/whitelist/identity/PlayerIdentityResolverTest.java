package com.baymc.whitelist.identity;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 针对白名单 UUID 来源解析规则的单元测试
 */
class PlayerIdentityResolverTest {
    @Test
    void offlineNameUuidMatchesBukkitOfflineAlgorithm() {
        UUID uuid = PlayerIdentityResolver.offlineNameUuid("Notch");

        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), uuid);
    }

    @Test
    void offlineNameUuidIsCaseSensitive() {
        UUID canonicalCase = PlayerIdentityResolver.offlineNameUuid("Notch");
        UUID lowerCase = PlayerIdentityResolver.offlineNameUuid("notch");

        assertNotEquals(canonicalCase, lowerCase);
        assertEquals(UUID.fromString("42653081-a90e-3475-b3d6-3550cdb43f8e"), lowerCase);
    }

    @Test
    void serverSourceUsesServerProvidedUuid() {
        UUID serverUuid = UUID.fromString("00000000-0000-0000-0000-000000000123");

        PlayerIdentity identity = PlayerIdentityResolver.fromServerIdentity(
                serverUuid,
                "Notch",
                PluginConfig.UuidSource.SERVER
        );

        assertEquals(serverUuid, identity.uuid());
    }
}
