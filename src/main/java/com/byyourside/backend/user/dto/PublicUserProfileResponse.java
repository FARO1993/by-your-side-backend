package com.byyourside.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

// A diferencia de UserResponse (que se usa solo en /me), este DTO nunca
// expone el email -- es el perfil que ve CUALQUIER usuario autenticado
// sobre otra persona, y el email es informacion privada.
public record PublicUserProfileResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        Instant createdAt,
        long followersCount,
        long followingCount,
        boolean followedByCurrentUser
) {
}