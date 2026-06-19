package com.baymc.whitelist.code;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 对某个玩家 UUID 校验邀请码后的结果
 *
 * @param status 校验结果, 命令层会据此选择语言文件 key
 * @param normalizedCode 校验成功后写入数据库的标准化邀请码
 * @param issueDate 匹配到的签发日期, 仅有效邀请码存在
 * @param expiresAt 匹配签发日期对应的过期时间
 */
public record VerificationResult(
        Status status,
        String normalizedCode,
        LocalDate issueDate,
        ZonedDateTime expiresAt
) {
    /**
     * 创建前缀或 Base32 格式不匹配的结果
     *
     * @return 格式无效的校验结果
     */
    public static VerificationResult invalidFormat() {
        return new VerificationResult(Status.INVALID_FORMAT, null, null, null);
    }

    /**
     * 创建格式正确但不匹配任何有效签发日期的结果
     *
     * @return 签名无效或已过期的校验结果
     */
    public static VerificationResult invalidOrExpired() {
        return new VerificationResult(Status.INVALID_OR_EXPIRED, null, null, null);
    }

    /**
     * 创建包含标准化邀请码和匹配日期窗口的成功结果
     *
     * @param normalizedCode 写入审计日志和数据库的标准化邀请码
     * @param issueDate 邀请码匹配到的签发日期
     * @param expiresAt 邀请码对应的过期时间
     * @return 有效邀请码校验结果
     */
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
