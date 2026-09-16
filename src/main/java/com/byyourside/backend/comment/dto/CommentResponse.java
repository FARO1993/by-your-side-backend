package com.byyourside.backend.comment.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID postId,
        UserSummary author,
        String content,
        Instant createdAt
) {
}