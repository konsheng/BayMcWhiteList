package com.baymc.whitelist.storage;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 白名单玩家表中一行数据的快照
 *
 */
public record WhitelistRecord(
        String playerUuid,
        String playerName,
        String code,
        LocalDate issueDate,
        LocalDateTime usedAt,
        String sourceServer,
        LocalDateTime lastSeenAt
) {
}
