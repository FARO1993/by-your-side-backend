package com.byyourside.backend.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Size(max = 100)
        String displayName,

        @Size(max = 500)
        String bio,

        String avatarUrl
) {
}