package com.baymc.whitelist.command.target;

import org.jetbrains.annotations.Nullable;

/**
 * 状态查询或移除命令最终进入仓库前的查询目标
 *
 */
public record LookupTarget(String input, @Nullable String playerUuid, boolean resolveMojangName) {
}
