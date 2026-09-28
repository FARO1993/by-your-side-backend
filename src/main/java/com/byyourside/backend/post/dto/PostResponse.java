package com.byyourside.backend.post.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

// Nota de nombres: este record es "la respuesta HTTP que representa un
// post" -- sin relacion con com.byyourside.backend.postresponse.PostResponse
// (la entidad de respuesta TIPADA a un post, Backend Debt B1), que
// coincide en nombre por casualidad de vocabulario. Ningun archivo necesita
// importar ambas clases a la vez.
//
// `supportCount`/`supportedByCurrentUser` (LEGACY, Backend Debt B1 § 12,
// opcion A): se mantienen por compatibilidad con quien ya consumia estos
// campos -- ahora derivados de presenceCount+listeningCount y de
// currentUserResponseType respectivamente, nunca una fuente de verdad
// aparte. `presenceCount`/`listeningCount`/`currentUserResponseType` son los
// campos nuevos (adicion pura al final, no rompe contrato) -- ver
// docs/API_CONTRACT.md para el detalle completo.
public record PostResponse(
        UUID id,
        UserSummary author,
        String content,
        String visibility,
        Instant createdAt,
        Instant updatedAt,
        boolean followedByCurrentUser,
        long supportCount,
        boolean supportedByCurrentUser,
        long presenceCount,
        long listeningCount,
        String currentUserResponseType
) {
}
