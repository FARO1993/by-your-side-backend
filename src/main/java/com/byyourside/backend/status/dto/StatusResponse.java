package com.byyourside.backend.status.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record StatusResponse(
        UUID id,
        UserSummary user,
        String mood,
        Instant createdAt,
        Instant expiresAt,
        long reactionCount,
        String reactedByCurrentUser // null si el usuario actual no reacciono todavia
) {
}