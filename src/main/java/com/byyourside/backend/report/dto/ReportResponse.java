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
        Instant reviewedAt,
        // V20: autor REAL del post reportado. Solo se completa en las rutas de
        // moderacion (cola y resolver) -- nunca en la respuesta a quien
        // reporta, para no des-anonimizar un post anonimo. null si el target
        // no es un POST o si el post ya no existe.
        UUID targetAuthorId
) {
}