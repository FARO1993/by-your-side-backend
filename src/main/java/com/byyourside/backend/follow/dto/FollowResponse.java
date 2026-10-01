package com.byyourside.backend.follow.dto;

import java.time.Instant;
import java.util.UUID;

// followState (Fase 9.3): "FOLLOWING" si el perfil objetivo es PUBLIC (Follow
// inmediato) o "REQUESTED" si es PRIVATE (queda una FollowRequest pendiente
// de aceptacion) -- nunca "NONE" en esta respuesta, ese estado es solo
// relevante en el perfil/discover, no como resultado directo de un POST
// exitoso a este endpoint.
//
// requestId: null cuando followState es "FOLLOWING" (no hay tramite, ya es
// una relacion efectiva); poblado cuando es "REQUESTED", para que el
// frontend pueda ofrecer "cancelar solicitud" de inmediato sin tener que
// llamar primero a GET /api/follow-requests/outgoing solo para descubrir el
// id que acaba de crear.
public record FollowResponse(
        UUID followerId,
        UUID followingId,
        Instant createdAt,
        String followState,
        UUID requestId
) {
}