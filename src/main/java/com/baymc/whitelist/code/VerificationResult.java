package com.baymc.whitelist.code;

import java.time.LocalDate;
import java.time.ZonedDateTime;

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

    public enum Status {
        VALID,
        INVALID_FORMAT,
        INVALID_OR_EXPIRED
    }
}
