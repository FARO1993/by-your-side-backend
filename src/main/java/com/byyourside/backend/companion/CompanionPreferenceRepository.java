package com.byyourside.backend.companion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface CompanionPreferenceRepository extends JpaRepository<CompanionPreference, UUID> {

    // Proyeccion directa del enum, sin hidratar User -- mismo patron que
    // UserBlockRepository.findBlockedIdsByBlocker. Sin N+1: una sola query
    // para todas las preferences de un usuario.
    @Query("SELECT p.type FROM CompanionPreference p WHERE p.user.id = :userId")
    Set<CompanionPreferenceType> findTypesByUserId(@Param("userId") UUID userId);

    void deleteByUserId(UUID userId);
}
