package com.baymc.whitelist.command.target;

/**
 * 目标身份的本地解析来源
 */
public enum TargetSource {
    /**
     * 从当前在线玩家实体读取 UUID 和名称
     */
    ONLINE,
    /**
     * 从 UUID 输入或 offline-name 算法本地计算得到
     */
    LOCAL
}
