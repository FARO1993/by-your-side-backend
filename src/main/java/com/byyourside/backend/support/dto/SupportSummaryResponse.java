package com.byyourside.backend.support.dto;

import java.util.UUID;

public record SupportSummaryResponse(
        UUID postId,
        long supportCount,
        boolean supportedByCurrentUser
) {
}