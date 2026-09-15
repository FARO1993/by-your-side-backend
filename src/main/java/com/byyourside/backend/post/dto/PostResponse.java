package com.byyourside.backend.post.dto;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(
        UUID id,
        AuthorSummary author,
        String content,
        String visibility,
        Instant createdAt,
        Instant updatedAt
) {
}