package com.baymc.whitelist.command.target;

import org.jetbrains.annotations.Nullable;

/**
 * 状态查询或移除命令最终进入仓库前的查询目标
 *
 * @param input 管理员输入的原始目标文本
 * @param playerUuid 已解析出的标准 UUID 字符串, 需要 Mojang 查询时暂为空
 * @param resolveMojangName 是否还需要按正版名称查询 UUID
 */
public record LookupTarget(String input, @Nullable String playerUuid, boolean resolveMojangName) {
}
