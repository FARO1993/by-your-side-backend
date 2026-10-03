package com.byyourside.backend.user.dto;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String username,
        String displayName,
        String avatarId
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
