package com.byyourside.backend.follow.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

// Forma comun para incoming/outgoing y para el resultado de accept/reject:
// `otherUser` es el requester en un listado de incoming, o el target en uno
// de outgoing -- siempre "la otra persona involucrada en este tramite".
// `status` es el FollowRequestStatus real (PENDING/ACCEPTED/REJECTED/
// CANCELLED), no el FollowState resumido de FollowResponse -- responden
// preguntas distintas.
public record FollowRequestResponse(
        UUID requestId,
        UserSummary otherUser,
        Instant createdAt,
        String status
) {
}
