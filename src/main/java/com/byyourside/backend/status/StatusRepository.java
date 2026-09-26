package com.byyourside.backend.status;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusRepository extends JpaRepository<Status, UUID> {

    // Trae todos los estados activos (no vencidos) de una lista de usuarios,
    // ordenados por usuario y luego por mas reciente -- el service se queda
    // solo con el primero de cada usuario (su estado actual real).
    //
    // Fase 9.4: NOT EXISTS de bloqueo bilateral -- defensa en profundidad,
    // igual que PostRepository.findFeedForUser (:userIds ya nunca deberia
    // contener a alguien bloqueado, porque BlockService.blockUser limpia
    // `follows` en ambas direcciones, pero el filtro explicito en la query
    // no depende de esa invariante para seguir siendo correcto).
    @Query("""
            SELECT s FROM Status s
            JOIN FETCH s.user
            WHERE s.user.id IN :userIds
            AND s.expiresAt > :now
            AND NOT EXISTS (
                SELECT 1 FROM UserBlock b
                WHERE (b.blocker.id = :currentUserId AND b.blocked.id = s.user.id)
                OR (b.blocker.id = s.user.id AND b.blocked.id = :currentUserId)
            )
            ORDER BY s.user.id, s.createdAt DESC
            """)
    List<Status> findActiveStatusesForUsers(@Param("userIds") List<UUID> userIds,
                                             @Param("now") Instant now,
                                             @Param("currentUserId") UUID currentUserId);

    Optional<Status> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);
}