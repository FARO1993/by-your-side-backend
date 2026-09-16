package com.byyourside.backend.post.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(
        UUID id,
        UserSummary author,
        String content,
        String visibility,
        Instant createdAt,
        Instant updatedAt
) {
}