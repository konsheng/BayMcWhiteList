package com.baymc.whitelist.code;

import com.baymc.whitelist.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InviteCodeServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void generatedCodeVerifiesForSamePlayer() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
        assertEquals(generated.code(), result.normalizedCode());
    }

    @Test
    void generatedCodeDoesNotVerifyForAnotherPlayer() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code(), "player-two");

        assertEquals(VerificationResult.Status.INVALID_OR_EXPIRED, result.status());
    }

    @Test
    void lowercaseInputIsAcceptedWhenCaseInsensitive() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = service.generate("player-one");
        VerificationResult result = service.verify(generated.code().toLowerCase(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
    }

    @Test
    void malformedInputIsRejectedBeforeSignatureCheck() {
        InviteCodeService service = serviceAt(LocalDate.of(2026, 6, 16));

        VerificationResult result = service.verify("BAYMC-00000000", "player-one");

        assertEquals(VerificationResult.Status.INVALID_FORMAT, result.status());
    }

    @Test
    void seventhNaturalDayIsStillValid() {
        InviteCodeService generator = serviceAt(LocalDate.of(2026, 6, 10));
        InviteCodeService verifier = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = generator.generate("player-one");
        VerificationResult result = verifier.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.VALID, result.status());
    }

    @Test
    void eighthNaturalDayIsExpired() {
        InviteCodeService generator = serviceAt(LocalDate.of(2026, 6, 9));
        InviteCodeService verifier = serviceAt(LocalDate.of(2026, 6, 16));

        GeneratedCode generated = generator.generate("player-one");
        VerificationResult result = verifier.verify(generated.code(), "player-one");

        assertEquals(VerificationResult.Status.INVALID_OR_EXPIRED, result.status());
    }

    private static InviteCodeService serviceAt(LocalDate date) {
        Clock clock = Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
        return new InviteCodeService(settings(), clock);
    }

    private static PluginConfig.CodeSettings settings() {
        return new PluginConfig.CodeSettings(
                "BAYMC",
                "unit-test-secret-with-enough-entropy",
                8,
                7,
                ZONE,
                false
        );
    }
}
