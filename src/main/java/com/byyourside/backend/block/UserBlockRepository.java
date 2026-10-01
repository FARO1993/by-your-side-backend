package com.byyourside.backend.block;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    Page<UserBlock> findByBlockerIdOrderByCreatedAtDesc(UUID blockerId, Pageable pageable);

    // Chequeo bilateral en UNA sola consulta (no dos existsBy... por
    // direccion): OR sobre ambas direcciones, cubierto por el indice del
    // unique constraint (blocker_id, blocked_id) para el sentido directo y
    // por idx_user_blocks_blocked_id para el inverso. Este es el metodo que
    // usa BlockPolicy.isBlockedBetween -- el unico punto central que el resto
    // del codigo debe llamar en vez de duplicar el OR en cada caller.
    @Query("""
            SELECT COUNT(b) > 0 FROM UserBlock b
            WHERE (b.blocker.id = :userA AND b.blocked.id = :userB)
            OR (b.blocker.id = :userB AND b.blocked.id = :userA)
            """)
    boolean existsBilateral(@Param("userA") UUID userA, @Param("userB") UUID userB);

    // Batch para discover (2 consultas para toda la pagina, no N+1): la union
    // de "a quien bloquee" + "quien me bloqueo" se arma en Java (UserService),
    // ya que JPQL no soporta UNION -- mismo criterio que el resto del batching
    // de esta capa (ver FollowRequestRepository.findPendingOutgoingTargetIdsAmong).
    @Query("SELECT b.blocked.id FROM UserBlock b WHERE b.blocker.id = :userId")
    Set<UUID> findBlockedIdsByBlocker(@Param("userId") UUID userId);

    @Query("SELECT b.blocker.id FROM UserBlock b WHERE b.blocked.id = :userId")
    Set<UUID> findBlockerIdsByBlocked(@Param("userId") UUID userId);
}
