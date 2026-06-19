package com.baymc.whitelist.command.target;

import java.util.Optional;
import java.util.UUID;

/**
 * 命令输入中的玩家目标文本和可选 UUID 解析结果
 *
 * @param text 去除首尾空白后的原始输入
 * @param uuid 输入本身是 UUID 时的解析结果
 */
public record TargetInput(String text, Optional<UUID> uuid) {
}
