package com.byyourside.backend.block.dto;

import java.time.Instant;
import java.util.UUID;

// DTO minimo a proposito: GET /api/users/me/blocked solo lista a quien EL
// USUARIO ACTUAL bloqueo (nunca quien lo bloqueo a el) -- nunca expone email,
// igual que UserSummary.
public record BlockedUserResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        Instant blockedAt
) {
}
