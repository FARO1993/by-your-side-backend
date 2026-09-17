package com.byyourside.backend.notification.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UserSummary actor,
        String type,
        UUID postId,
        boolean read,
        Instant createdAt
) {
}