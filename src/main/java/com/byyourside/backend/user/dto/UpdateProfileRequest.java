package com.byyourside.backend.user.dto;

import com.byyourside.backend.user.ProfileVisibility;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Size(max = 100)
        String displayName,

        @Size(max = 500)
        String bio,

        String avatarUrl,

        // Opcional: si viene null, no se toca la visibilidad actual (mismo
        // criterio "null = no tocar" que el resto de los campos de este DTO).
        ProfileVisibility profileVisibility
) {
}