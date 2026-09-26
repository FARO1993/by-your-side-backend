package com.byyourside.backend.user.dto;

import java.util.UUID;

// `bio` viaja en null cuando profileVisibility es PRIVATE -- mismo criterio
// que PublicUserProfileResponse, para no exponer el mismo dato por una ruta
// lateral (discover) que la vista de perfil ya oculta.
public record DiscoverUserResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        String profileVisibility
) {
}