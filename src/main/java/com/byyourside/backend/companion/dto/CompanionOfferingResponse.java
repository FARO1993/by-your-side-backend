package com.byyourside.backend.companion.dto;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B4B.2: sin UserSummary, mismo criterio que
// CompanionNeedResponse -- /mine siempre resuelve contra el propio usuario
// autenticado, "de quien es" es implicito.
public record CompanionOfferingResponse(
        UUID id,
        String type,
        Instant createdAt,
        Instant expiresAt
) {
}
