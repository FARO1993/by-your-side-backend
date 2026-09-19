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
    @Query(value = """
            SELECT * FROM availabilities a
            WHERE a.intent = :intent
            AND a.expires_at > :now
            AND a.user_id != :excludeUserId
            ORDER BY RANDOM()
            LIMIT :limit
            """, nativeQuery = true)
    List<Availability> findRandomAvailable(@Param("intent") String intent,
                                           @Param("now") Instant now,
                                           @Param("excludeUserId") UUID excludeUserId,
                                           @Param("limit") int limit);
}