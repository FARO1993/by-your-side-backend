package com.byyourside.backend.user.dto;

import java.util.UUID;

public record DiscoverUserResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl
) {
}