package com.byyourside.backend.status;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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
    // Fase 9.5: NOT EXISTS de UserMute, UNILATERAL (solo :currentUserId
    // como muter) -- status/presence agregado es una superficie de
    // descubrimiento igual que el feed de posts, asi que aplica el mismo
    // criterio de "afecta lo agregado, no el acceso directo" (no existe una
    // ruta de acceso directo a un status individual ajeno hoy, pero
    // react()/removeReaction() siguen sin chequear mute, ver StatusService).
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
            AND NOT EXISTS (
                SELECT 1 FROM UserMute m
                WHERE m.muter.id = :currentUserId AND m.muted.id = s.user.id
            )
            ORDER BY s.user.id, s.createdAt DESC
            """)
    List<Status> findActiveStatusesForUsers(@Param("userIds") List<UUID> userIds,
                                             @Param("now") Instant now,
                                             @Param("currentUserId") UUID currentUserId);

    Optional<Status> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    // Backend Debt B5.4A: status summary de Discover -- UNA query por pagina,
    // devuelve a lo sumo UNA fila por usuario (su mood vigente mas reciente).
    // DISTINCT ON (Postgres, mismo criterio que los ORDER BY RANDOM() nativos
    // del proyecto): un usuario puede tener VARIAS filas vigentes (cada
    // POST /api/statuses crea una nueva y las anteriores siguen activas hasta
    // expirar), y la actual es la de created_at mas reciente; el desempate
    // por id DESC hace el resultado determinista si dos comparten created_at.
    // Mismo criterio de "activo" que findTopByUserIdAndExpiresAtAfter...
    // (expires_at estrictamente posterior a :now).
    //
    // SIN filtros de block/mute/visibilidad: :userIds llega ya autorizado
    // (UserService.discoverUsers solo pasa los ids cuyo perfil completo es
    // visible para el viewer). Solo userId + mood, nunca el Status completo.
    @Query(value = """
            SELECT DISTINCT ON (s.user_id)
                s.user_id AS userId,
                s.mood AS mood
            FROM statuses s
            WHERE s.user_id IN (:userIds)
            AND s.expires_at > :now
            ORDER BY s.user_id, s.created_at DESC, s.id DESC
            """, nativeQuery = true)
    List<StatusMoodProjection> findCurrentMoodsForUsers(@Param("userIds") Collection<UUID> userIds,
                                                        @Param("now") Instant now);
}