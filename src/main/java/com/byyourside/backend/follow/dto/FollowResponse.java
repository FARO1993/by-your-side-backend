package com.byyourside.backend.follow.dto;

import java.time.Instant;
import java.util.UUID;

public record FollowResponse(
        UUID followerId,
        UUID followingId,
        Instant createdAt
) {
}