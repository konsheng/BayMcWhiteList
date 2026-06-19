package com.baymc.whitelist.command.target;

import com.baymc.whitelist.identity.PlayerIdentity;

/**
 * 邀请码生成命令使用的目标身份及其解析来源
 *
 * @param identity 已解析到的玩家 UUID 和展示名称
 * @param source 身份来自在线玩家实体还是本地输入计算
 */
public record IdentityTarget(PlayerIdentity identity, TargetSource source) {
}
