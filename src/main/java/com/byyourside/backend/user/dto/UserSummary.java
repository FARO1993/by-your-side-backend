package com.byyourside.backend.user.dto;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String username,
        String displayName,
        String avatarUrl
) {
}