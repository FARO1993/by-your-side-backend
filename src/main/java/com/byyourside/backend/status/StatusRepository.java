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
    @Query("""
            SELECT s FROM Status s
            JOIN FETCH s.user
            WHERE s.user.id IN :userIds
            AND s.expiresAt > :now
            ORDER BY s.user.id, s.createdAt DESC
            """)
    List<Status> findActiveStatusesForUsers(@Param("userIds") List<UUID> userIds, @Param("now") Instant now);

    Optional<Status> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);
}