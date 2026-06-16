package com.baymc.whitelist.storage;

/**
 * 数据库字段长度上限
 *
 * <p>建表语句和审计日志防御性截断共用这些常量, 避免表结构和写入边界分散在不同文件中
 */
final class StorageLimits {
    /** whitelist_players.player_key 和 whitelist_logs.player_key 的最大长度 */
    static final int PLAYER_KEY = 64;
    /** whitelist_players.player_uuid 的最大长度 */
    static final int PLAYER_UUID = 36;
    /** 玩家名字段的最大长度, 与 Minecraft 玩家名边界保持一致 */
    static final int PLAYER_NAME = 32;
    /** 审计动作代码字段的最大长度 */
    static final int ACTION = 32;
    /** 邀请码字段的最大长度, 覆盖最长前缀, 分隔符和 64 位后缀 */
    static final int CODE = 96;
    /** 服务器名称字段的最大长度 */
    static final int SERVER_NAME = 64;
    /** 远程地址文本字段的最大长度 */
    static final int IP = 64;
    /** 审计诊断消息字段的最大长度 */
    static final int MESSAGE = 255;

    private StorageLimits() {
    }
}
