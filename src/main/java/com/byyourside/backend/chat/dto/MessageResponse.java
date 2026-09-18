package com.byyourside.backend.chat.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        UserSummary sender,
        String content,
        boolean read,
        Instant createdAt
) {
}