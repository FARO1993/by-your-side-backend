package com.byyourside.backend.auth.dto;

import java.time.Instant;

public record EmailVerificationResponse(
        boolean emailVerified,
        Instant emailVerifiedAt
) {
}
