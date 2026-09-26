package com.byyourside.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

// A diferencia de UserResponse (que se usa solo en /me), este DTO nunca
// expone el email -- es el perfil que ve CUALQUIER usuario autenticado
// sobre otra persona, y el email es informacion privada.
//
// Cuando profileVisibility es PRIVATE y el viewer no es un follower
// ACEPTADO (ni el propio usuario), `bio` viaja en null -- vista "limitada"
// (Fase 9.1, extendida en 9.3): el frontend debe mostrar nombre/avatar + un
// aviso de perfil privado + la accion de follow que corresponda segun
// `followState`, en vez del contenido completo del perfil. Un follower ya
// ACEPTADO de un perfil PRIVATE SI ve el perfil completo (Fase 9.3).
// followersCount/followingCount NO se ocultan a proposito (ocultar
// contadores es una decision de producto separada, fuera de esta fase).
//
// `followedByCurrentUser` (sin cambios de significado: true solo si existe
// una relacion Follow real/aceptada) y `followState` (Fase 9.3: "NONE" |
// "REQUESTED" | "FOLLOWING") conviven a proposito -- `followState` es la
// fuente de verdad mas completa, `followedByCurrentUser` se conserva sin
// romper para quien ya integraba contra Fase 9.1/9.2. Nunca pueden
// contradecirse: `followedByCurrentUser == (followState == "FOLLOWING")`
// siempre, porque ambos se derivan del mismo chequeo.
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
        String profileVisibility,
        String followState
) {
}