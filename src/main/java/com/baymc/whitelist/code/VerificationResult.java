package com.baymc.whitelist.code;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 对某个玩家 UUID 校验邀请码后的结果
 *
 */
public record VerificationResult(
        Status status,
        String normalizedCode,
        LocalDate issueDate,
        ZonedDateTime expiresAt
) {
    public static VerificationResult invalidFormat() {
        return new VerificationResult(Status.INVALID_FORMAT, null, null, null);
    }

    public static VerificationResult invalidOrExpired() {
        return new VerificationResult(Status.INVALID_OR_EXPIRED, null, null, null);
    }

    public static VerificationResult valid(String normalizedCode, LocalDate issueDate, ZonedDateTime expiresAt) {
        return new VerificationResult(Status.VALID, normalizedCode, issueDate, expiresAt);
    }

    /**
     * 简洁的状态枚举, 避免在语言文件之外暴露提示文本
     */
    public enum Status {
        /** 邀请码匹配当前玩家和某个可接受的签发日期  */
        VALID,
        /** 提交的字符串不符合 BayMC 邀请码格式  */
        INVALID_FORMAT,
        /** 提交的字符串格式正确, 但没有有效签名匹配  */
        INVALID_OR_EXPIRED
    }
}
