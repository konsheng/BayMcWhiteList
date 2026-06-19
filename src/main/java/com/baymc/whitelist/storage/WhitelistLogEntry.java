package com.baymc.whitelist.storage;

import java.time.LocalDateTime;

/**
 * 白名单验证, 加入拦截和管理员操作对应的追加式审计记录
 *
 */
public record WhitelistLogEntry(
        String playerUuid,
        String playerName,
        String action,
        String code,
        String serverName,
        String ip,
        String message,
        LocalDateTime createdAt
) {
}
