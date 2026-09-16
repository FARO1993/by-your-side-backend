package com.byyourside.backend.report.dto;

import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID reporterId,
        String targetType,
        UUID targetId,
        String reason,
        String description,
        String status,
        UUID reviewedById,
        Instant createdAt,
        Instant reviewedAt
) {
}