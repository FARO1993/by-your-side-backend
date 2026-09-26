package com.byyourside.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

// A diferencia de UserResponse (que se usa solo en /me), este DTO nunca
// expone el email -- es el perfil que ve CUALQUIER usuario autenticado
// sobre otra persona, y el email es informacion privada.
//
// Cuando profileVisibility es PRIVATE y el viewer no es el propio usuario,
// `bio` viaja en null -- vista "limitada" (Fase 9.1): el frontend debe
// mostrar nombre/avatar + un aviso de perfil privado + el boton de
// seguir/dejar de seguir, en vez del contenido completo del perfil.
// followersCount/followingCount NO se ocultan a proposito (ocultar
// contadores es una decision de producto separada, fuera de esta fase).
public record PublicUserProfileResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        Instant createdAt,
        long followersCount,
        long followingCount,
        boolean followedByCurrentUser,
        String profileVisibility
) {
}