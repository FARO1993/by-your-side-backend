package com.byyourside.backend.companion.dto;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B4B.1: a diferencia de AvailabilityResponse, no incluye
// UserSummary -- el Need nunca se expone publicamente (decision de diseño
// B4A #10), solo el propio owner lo consulta via /mine, asi que "de quien
// es" siempre es implicito (el usuario autenticado).
public record CompanionNeedResponse(
        UUID id,
        String type,
        Instant createdAt,
        Instant expiresAt
) {
}
