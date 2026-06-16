package com.baymc.whitelist.storage;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WhitelistRecord(
        String playerKey,
        String playerUuid,
        String playerName,
        String code,
        LocalDate issueDate,
        LocalDateTime usedAt,
        String sourceServer,
        LocalDateTime lastSeenAt
) {
}
