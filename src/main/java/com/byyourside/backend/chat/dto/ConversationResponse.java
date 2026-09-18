package com.byyourside.backend.chat.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UserSummary otherUser,
        String lastMessageContent,
        Instant lastMessageAt,
        long unreadCount
) {
}