package com.baymc.whitelist.command.target;

import com.baymc.whitelist.identity.PlayerIdentity;

/**
 * 邀请码生成命令使用的目标身份及其解析来源
 *
 */
public record IdentityTarget(PlayerIdentity identity, TargetSource source) {
}
