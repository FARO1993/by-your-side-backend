package com.byyourside.backend.user.dto;

import com.byyourside.backend.status.StatusMood;

import java.util.UUID;

// `bio` viaja en null cuando profileVisibility es PRIVATE -- mismo criterio
// que PublicUserProfileResponse, para no exponer el mismo dato por una ruta
// lateral (discover) que la vista de perfil ya oculta. `followState` (Fase
// 9.3): en browse nunca es "FOLLOWING" (browse excluye a quienes ya se
// sigue); en search (B5.1) puede ser NONE, REQUESTED o FOLLOWING.
//
// `available` (Backend Debt B5.2): true si el usuario tiene un
// CompanionOffering ACTIVO ahora (misma regla temporal que
// GET /api/users/{userId}/availability). Nunca null: un boolean simple para
// que el frontend no distinga "desconocido/oculto/no disponible". A
// proposito NO expone el OfferingType (LISTEN/TALK/DISTRACT) ni nada del
// Need -- eso queda en Companion/Public Availability. Es independiente de
// profileVisibility: un perfil PRIVATE puede estar available y aun asi
// `bio` sigue en null (no desbloquea datos de perfil).
//
// `statusMood` (Backend Debt B5.4A): mood del status ACTIVO mas reciente
// (expiresAt > now), o null. Es null tanto si no hay status activo como si
// el viewer no puede ver el perfil completo (PRIVATE sin follow aceptado) --
// el mismo gate que GET /api/users/{userId}/status. A proposito NO existe un
// `hasStatus`: un booleano filtraria la existencia de un status en un perfil
// privado. Nada mas del Status viaja aca (ni id, createdAt, expiresAt ni
// reacciones). Independiente de `available`.
public record DiscoverUserResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarId,
        String profileVisibility,
        String followState,
        boolean available,
        StatusMood statusMood
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
