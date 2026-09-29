package com.byyourside.backend.companion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanionOfferingRepository extends JpaRepository<CompanionOffering, UUID> {

    void deleteByUserId(UUID userId);

    Optional<CompanionOffering> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    // Backend Debt B4B.3: usado por CompanionOfferingService.hasActiveOffering,
    // consumido por ChatService para decidir el primer contacto -- mismo
    // criterio de eficiencia que la extinta
    // AvailabilityRepository.existsByUserIdAndExpiresAtAfter (EXISTS, no
    // trae la fila completa).
    boolean existsByUserIdAndExpiresAtAfter(UUID userId, Instant now);

    // ORDER BY RANDOM() nativo de Postgres -- mismo criterio deliberado que
    // AvailabilityRepository.findRandomAvailable: nunca ordenar por
    // popularidad en modo compañia. MVP a proposito (Backend Debt B4B.2):
    // sin ranking, sin relevancia, sin paginacion, sin scoring.
    //
    // Decision B4A #3: SIN filtro de ProfileVisibility/Follow -- activar un
    // Offering es consentimiento especifico para aparecer en superficies de
    // Companion, incluso con perfil PRIVATE y sin accepted follower. Nunca
    // desbloquea perfil completo/bio/posts/status -- el JOIN solo trae los
    // 4 campos de UserSummary.
    //
    // Bloqueo bilateral (NOT EXISTS sobre user_blocks, ambas direcciones) y
    // mute unilateral (NOT EXISTS sobre user_mutes, solo :excludeUserId
    // como muter_id) -- mismo patron exacto que la query legacy.
    @Query(value = """
            SELECT
                o.id AS offeringId,
                u.id AS userId,
                u.username AS username,
                u.display_name AS displayName,
                u.avatar_url AS avatarUrl,
                o.type AS offeringType,
                o.created_at AS createdAt,
                o.expires_at AS expiresAt
            FROM companion_offerings o
            JOIN users u ON u.id = o.user_id
            WHERE o.type = :type
            AND o.expires_at > :now
            AND o.user_id != :excludeUserId
            AND NOT EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_id = :excludeUserId AND b.blocked_id = o.user_id)
                OR (b.blocker_id = o.user_id AND b.blocked_id = :excludeUserId)
            )
            AND NOT EXISTS (
                SELECT 1 FROM user_mutes m
                WHERE m.muter_id = :excludeUserId AND m.muted_id = o.user_id
            )
            ORDER BY RANDOM()
            LIMIT :limit
            """, nativeQuery = true)
    List<CompanionCandidateProjection> findRandomCandidatesByType(@Param("type") String type,
                                                                   @Param("now") Instant now,
                                                                   @Param("excludeUserId") UUID excludeUserId,
                                                                   @Param("limit") int limit);
}
