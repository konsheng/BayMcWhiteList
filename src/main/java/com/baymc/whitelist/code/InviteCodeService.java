package com.baymc.whitelist.code;

import com.baymc.whitelist.config.PluginConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * 使用 HMAC-SHA256 生成并校验绑定玩家的邀请码
 *
 * <p>除配置和时钟外, 该服务刻意保持无状态, 测试可以注入固定时钟来锁定七天有效期窗口
 */
public final class InviteCodeService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final PluginConfig.CodeSettings settings;
    private final Clock clock;

    public InviteCodeService(PluginConfig.CodeSettings settings) {
        this(settings, Clock.system(settings.zoneId()));
    }

    public InviteCodeService(PluginConfig.CodeSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock.withZone(settings.zoneId());
    }

    public GeneratedCode generate(String playerUuid) {
        LocalDate issueDate = LocalDate.now(clock);
        String code = settings.prefix() + "-" + suffixFor(playerUuid, issueDate);
        return new GeneratedCode(code, issueDate, expiresAt(issueDate));
    }

    public VerificationResult verify(String rawCode, String playerUuid) {
        String trimmed = rawCode == null ? "" : rawCode.trim();
        String expectedPrefix = settings.prefix();
        String comparableCode = settings.caseSensitive() ? trimmed : trimmed.toUpperCase(Locale.ROOT);
        String comparablePrefix = settings.caseSensitive() ? expectedPrefix : expectedPrefix.toUpperCase(Locale.ROOT);

        String prefixWithSeparator = comparablePrefix + "-";
        if (!comparableCode.startsWith(prefixWithSeparator)) {
            return VerificationResult.invalidFormat();
        }

        String suffix = comparableCode.substring(prefixWithSeparator.length());
        if (suffix.length() != settings.suffixLength() || !isBase32Suffix(suffix)) {
            return VerificationResult.invalidFormat();
        }

        // 从今天回溯 validDays - 1 天即可让邀请码自然过期
        // 不需要把未使用的邀请码提前存入数据库
        LocalDate today = LocalDate.now(clock);
        for (int daysAgo = 0; daysAgo < settings.validDays(); daysAgo++) {
            LocalDate issueDate = today.minusDays(daysAgo);
            String expectedSuffix = suffixFor(playerUuid, issueDate);
            if (constantTimeEquals(suffix, expectedSuffix)) {
                return VerificationResult.valid(expectedPrefix + "-" + expectedSuffix, issueDate, expiresAt(issueDate));
            }
        }

        return VerificationResult.invalidOrExpired();
    }

    private String suffixFor(String playerUuid, LocalDate issueDate) {
        String payload = playerUuid + ":" + issueDate + ":" + settings.prefix();
        byte[] digest = hmac(payload);
        return base32(digest).substring(0, settings.suffixLength());
    }

    private ZonedDateTime expiresAt(LocalDate issueDate) {
        return issueDate
                .plusDays(settings.validDays())
                .atStartOfDay(settings.zoneId())
                .minusSeconds(1);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec key = new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate invite code HMAC", exception);
        }
    }

    private static String base32(byte[] bytes) {
        StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;

        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }

        if (bitsLeft > 0) {
            output.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return output.toString();
    }

    private static boolean isBase32Suffix(String suffix) {
        for (int index = 0; index < suffix.length(); index++) {
            char character = suffix.charAt(index);
            boolean letter = character >= 'A' && character <= 'Z';
            boolean digit = character >= '2' && character <= '7';
            if (!letter && !digit) {
                return false;
            }
        }
        return true;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
