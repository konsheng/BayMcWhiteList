package com.baymc.whitelist.storage;

import java.time.LocalDateTime;

/**
 * 白名单验证, 加入拦截和管理员操作对应的追加式审计记录
 *
 * @param playerUuid 参与该动作的玩家 UUID
 * @param playerName 展示给管理员的玩家名
 * @param action 存入审计表的稳定动作代码
 * @param code 相关的提交或接受的邀请码
 * @param serverName 产生日志的配置服务器名
 * @param ip 可用时记录的远程地址
 * @param message 简短诊断信息, 仓库层会截断到字段长度
 * @param createdAt 按插件配置时区记录的动作时间
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
