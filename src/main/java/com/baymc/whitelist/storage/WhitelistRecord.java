package com.baymc.whitelist.storage;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 白名单玩家表中一行数据的快照
 *
 * @param playerUuid 用于唯一白名单检查的玩家 UUID
 * @param playerName 最近一次记录的玩家名
 * @param code 创建或刷新该记录的邀请码
 * @param issueDate 邀请码校验匹配到的签发日期
 * @param usedAt 记录验证通过的时间
 * @param sourceServer 接受验证的服务器名
 * @param lastSeenAt 最近一次受保护服务器登录检查时间
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
