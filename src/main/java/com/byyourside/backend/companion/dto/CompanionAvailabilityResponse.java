package com.byyourside.backend.companion.dto;

import java.time.Instant;

// Backend Debt B4B.4: DTO minimo de "disponibilidad publica" -- nunca
// UserSummary (el caller ya sabe de quien esta preguntando, es el userId
// del path), nunca Need, nunca datos de perfil. `available` es
// deliberadamente explicito (no solo inferido de que el objeto no sea
// null) para que el contrato quede semantico y no obligue al frontend a
// inferir disponibilidad de la sola presencia del campo. `offeringType`
// es singular -- a lo sumo existe UNA Offering activa por usuario (ver
// UNIQUE(user_id) en companion_offerings), nunca una lista. Sin
// `createdAt`: el frontend solo necesita saber que esta disponible, como,
// y hasta cuando -- no cuando se activo.
public record CompanionAvailabilityResponse(
        boolean available,
        String offeringType,
        Instant expiresAt
) {
}
