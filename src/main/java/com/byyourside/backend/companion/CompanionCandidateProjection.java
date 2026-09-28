package com.byyourside.backend.companion;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B4B.2: proyeccion de la query nativa de busqueda -- trae los
// datos de usuario en el mismo round-trip (ver
// CompanionOfferingRepository.findRandomCandidatesByType), a diferencia de
// AvailabilityRepository.findRandomAvailable (que devuelve la entidad
// completa y dispara un lazy-load de User por fila al armar la respuesta).
public interface CompanionCandidateProjection {
    UUID getUserId();

    String getUsername();

    String getDisplayName();

    String getAvatarUrl();

    String getOfferingType();

    Instant getExpiresAt();
}
