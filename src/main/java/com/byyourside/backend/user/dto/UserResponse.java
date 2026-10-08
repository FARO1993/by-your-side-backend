package com.byyourside.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String email,
        String displayName,
        String bio,
        String avatarId,
        String role,
        Instant createdAt,
        boolean emailVerified,
        Instant emailVerifiedAt,
        String profileVisibility
) {
}
