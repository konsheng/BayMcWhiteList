package com.baymc.whitelist.command.target;

import com.baymc.whitelist.identity.PlayerIdentity;

/**
 * 手动添加白名单命令最终要写入的目标身份
 *
 * @param identity 已解析到的玩家 UUID 和展示名称
 * @param profileVerified 是否已经通过 Mojang 档案查询确认
 */
public record AddTarget(PlayerIdentity identity, boolean profileVerified) {
}
