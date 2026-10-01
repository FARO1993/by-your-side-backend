package com.byyourside.backend.mute.dto;

import java.time.Instant;
import java.util.UUID;

// DTO minimo a proposito, mismo criterio que
// com.byyourside.backend.block.dto.BlockedUserResponse: GET
// /api/users/me/muted solo lista a quien EL USUARIO ACTUAL muteo (nunca
// quien lo muteo a el) -- nunca expone email, ni cuantos usuarios lo
// mutearon a el.
public record MutedUserResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        Instant mutedAt
) {
}
