package com.baymc.whitelist.code;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 为某个玩家 UUID 生成邀请码后返回的不可变值对象
 *
 */
public record GeneratedCode(String code, LocalDate issueDate, ZonedDateTime expiresAt) {
}
