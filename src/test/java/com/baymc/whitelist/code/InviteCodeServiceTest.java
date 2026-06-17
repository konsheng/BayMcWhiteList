package com.baymc.whitelist.code;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 针对邀请码签名窗口的确定性单元测试
 */
class InviteCodeServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 为某个玩家生成的邀请码应该能被同一个玩家验证通过
     */
    @Test
    void generatedCodeVerifiesForSamePlayer() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
        assertEquals(generated.code(), result.normalizedCode());
    }

    /**
     * 玩家键是 HMAC 载荷的一部分, 因此邀请码不能被其他玩家共用
     */
    @Test
    void generatedCodeDoesNotVerifyForAnotherPlayer() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code(), "player-two");

        assertEquals(VerificationResult.Status.INVALID_OR_EXPIRED, result.status());
    }

    /**
     * 关闭大小写敏感模式时, 小写输入也应被接受
     */
    @Test
    void lowercaseInputIsAcceptedWhenCaseInsensitive() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code().toLowerCase(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
    }

    /**
     * 不属于生成器 Base32 字母表的后缀应被判定为格式错误
     */
    @Test
    void malformedInputIsRejectedBeforeSignatureCheck() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        VerificationResult result = service.verify("BAYMC-00000000", "player-one");

        assertEquals(VerificationResult.Status.INVALID_FORMAT, result.status());
    }

    /**
     * SHA-256 摘要的 Base32 输出最多 52 位, 配置允许的最大后缀长度也应能正常生成和校验
     */
    @Test
    void maximumSuffixLengthGeneratesAndVerifies() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16), 52);

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code(), "player-one");

        assertEquals("BAYMC-".length() + 52, generated.code().length());
        assertEquals(VerificationResult.Status.VALID, result.status());
    }

    /**
     * 配置的七天自然日窗口应包含第七天
     */
    @Test
    void seventhNaturalDayIsStillValid() {
        InviteCodeService generator = serviceAt(LocalDate.of(2026, 6, 10));
        InviteCodeService verifier = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = generator.generate("player-one");
        VerificationResult result = verifier.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
    }

    /**
     * 往前第八个自然日的邀请码应超出可接受窗口
     */
    @Test
    void eighthNaturalDayIsExpired() {
        InviteCodeService generator = serviceAt(LocalDate.of(2026, 6, 9));
        InviteCodeService verifier = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = generator.generate("player-one");
        VerificationResult result = verifier.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.INVALID_OR_EXPIRED, result.status());
    }

    /**
     * 创建时钟固定在 Asia/Shanghai 某个自然日的服务
     */
    private static InviteCodeService serviceAt(LocalDate date) {
        return serviceAt(date, 8);
    }

    private static InviteCodeService serviceAt(LocalDate date, int suffixLength) {
        Clock clock = Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
        return new InviteCodeService(settings(suffixLength), clock);
    }

    /**
     * 返回所有邀请码测试共用的配置
     */
    private static PluginConfig.CodeSettings settings() {
        return settings(8);
    }

    private static PluginConfig.CodeSettings settings(int suffixLength) {
        return new PluginConfig.CodeSettings(
                "BAYMC",
                "unit-test-secret-with-enough-entropy",
                suffixLength,
                7,
                ZONE,
                false
        );
    }
}
