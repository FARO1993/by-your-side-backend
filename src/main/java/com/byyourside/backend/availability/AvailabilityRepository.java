package com.byyourside.backend.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AvailabilityRepository extends JpaRepository<Availability, UUID> {

    void deleteByUserId(UUID userId);

    Optional<Availability> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    boolean existsByUserIdAndExpiresAtAfter(UUID userId, Instant now);

    // ORDER BY RANDOM() es nativo de Postgres -- no hay forma limpia de
    // pedir orden aleatorio en JPQL, asi que esta query es nativa a
    // proposito. El orden aleatorio es una decision de diseño: nunca
    // ordenar por popularidad/apoyo recibido en el modo compañia.
    //
    // Fase 9.4: exclusion bilateral de bloqueo directo en la query (no hay
    // forma de reusar BlockPolicy aca sin volver esto N+1 -- una consulta
    // por candidato). El NOT EXISTS cubre ambas direcciones con un solo OR,
    // igual que BlockPolicy.isBlockedBetween.
    // Fase 9.5: NOT EXISTS de user_mutes, UNILATERAL (solo :excludeUserId
    // como muter_id) -- si A mutea B, B deja de aparecer como sugerencia
    // para A, pero A sigue apareciendo normalmente para B (sin el OR
    // inverso que si tiene el bloqueo de arriba). No borra la fila de
    // availability de nadie, solo la excluye de este listado.
    @Query(value = """
            SELECT * FROM availabilities a
            WHERE a.intent = :intent
            AND a.expires_at > :now
            AND a.user_id != :excludeUserId
            AND NOT EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_id = :excludeUserId AND b.blocked_id = a.user_id)
                OR (b.blocker_id = a.user_id AND b.blocked_id = :excludeUserId)
            )
            AND NOT EXISTS (
                SELECT 1 FROM user_mutes m
                WHERE m.muter_id = :excludeUserId AND m.muted_id = a.user_id
            )
            ORDER BY RANDOM()
            LIMIT :limit
            """, nativeQuery = true)
    List<Availability> findRandomAvailable(@Param("intent") String intent,
                                           @Param("now") Instant now,
                                           @Param("excludeUserId") UUID excludeUserId,
                                           @Param("limit") int limit);
}