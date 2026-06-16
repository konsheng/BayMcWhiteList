package com.baymc.whitelist.storage;

import java.time.LocalDateTime;

public record WhitelistLogEntry(
        String playerKey,
        String playerName,
        String action,
        String code,
        String serverName,
        String ip,
        String message,
        LocalDateTime createdAt
) {
}
