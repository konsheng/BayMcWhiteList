package com.baymc.whitelist.code;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 为某个玩家标识生成邀请码后返回的不可变值对象
 *
 * @param code 面向玩家展示的完整邀请码, 包含配置中的前缀
 * @param issueDate 参与 HMAC 计算的自然日期
 * @param expiresAt 邀请码最后可验证自然日的结束时间
 */
public record GeneratedCode(String code, LocalDate issueDate, ZonedDateTime expiresAt) {
}
