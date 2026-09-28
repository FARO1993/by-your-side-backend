package com.byyourside.backend.companion.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;

// Backend Debt B4B.2: DTO minimo de candidato para busqueda de Companion --
// nunca la entidad completa. Solo lo que UserSummary ya expone en todo el
// resto de la API (nunca email, nunca datos de perfil interno) mas el tipo
// de Offering y su vencimiento. Nunca incluye el Need de nadie -- Need es
// siempre privado (decision B4A #10).
public record CompanionCandidateResponse(
        UserSummary user,
        String offeringType,
        Instant expiresAt
) {
}
