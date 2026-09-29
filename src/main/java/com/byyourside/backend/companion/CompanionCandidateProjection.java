package com.byyourside.backend.companion;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B4B.2: proyeccion de la query nativa de busqueda -- trae los
// datos de usuario en el mismo round-trip (ver
// CompanionOfferingRepository.findRandomCandidatesByType), a diferencia de
// la extinta AvailabilityRepository.findRandomAvailable (legacy, que
// devolvia la entidad completa y disparaba un lazy-load de User por fila
// al armar la respuesta).
//
// Backend Debt B4B.3: getOfferingId/getCreatedAt se agregan para que
// AvailabilityController (adapter legacy) pueda armar su AvailabilityResponse
// historico con datos reales -- CompanionCandidateResponse (el DTO publico
// del dominio nuevo) sigue sin exponerlos, esto no cambia el contrato de
// B4B.2.
public interface CompanionCandidateProjection {
    UUID getOfferingId();

    UUID getUserId();

    String getUsername();

    String getDisplayName();

    String getAvatarUrl();

    String getOfferingType();

    Instant getCreatedAt();

    Instant getExpiresAt();
}
