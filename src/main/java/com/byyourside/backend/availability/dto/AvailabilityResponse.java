package com.byyourside.backend.availability.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record AvailabilityResponse(
        UUID id,
        UserSummary user,
        String intent,
        Instant createdAt,
        Instant expiresAt
) {
}