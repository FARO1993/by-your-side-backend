package com.byyourside.backend.availability.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B4B.3: LEGACY -- shape historico de /api/availability/**,
// armado por el adapter (AvailabilityController) desde CompanionOffering.
// `intent` es un CompanionIntent traducido via LegacyAvailabilityMapper
// (lossy en el sentido Offering->Intent, ver ahi).
public record AvailabilityResponse(
        UUID id,
        UserSummary user,
        String intent,
        Instant createdAt,
        Instant expiresAt
) {
}