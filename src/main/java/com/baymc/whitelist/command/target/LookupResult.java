package com.baymc.whitelist.command.target;

import com.baymc.whitelist.storage.WhitelistRecord;

import java.util.Optional;

/**
 * 状态查询或移除命令的仓库查询结果
 *
 * @param record 找到的白名单记录, 不存在时为空
 */
public record LookupResult(Optional<WhitelistRecord> record) {
}
