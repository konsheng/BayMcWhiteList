package com.baymc.whitelist.command.target;

import com.baymc.whitelist.identity.PlayerIdentity;

/**
 * 手动添加白名单命令最终要写入的目标身份
 *
 */
public record AddTarget(PlayerIdentity identity, boolean profileVerified) {
}
