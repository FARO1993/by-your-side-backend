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

    /**
     * Transición: hasta que el frontend use avatarId, avatarUrl sale siempre
     * en null (ya no hay fotos). Se quita en el próximo PR.
     */
    @Deprecated(forRemoval = true)
    @com.fasterxml.jackson.annotation.JsonProperty("avatarUrl")
    public String legacyAvatarUrl() {
        return null;
    }
}
