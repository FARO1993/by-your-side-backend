package com.byyourside.backend.follow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowRequestRepository extends JpaRepository<FollowRequest, UUID> {

    Optional<FollowRequest> findByRequesterIdAndTargetIdAndStatus(UUID requesterId, UUID targetId, FollowRequestStatus status);

    boolean existsByRequesterIdAndTargetIdAndStatus(UUID requesterId, UUID targetId, FollowRequestStatus status);

    @Query("""
            SELECT r FROM FollowRequest r JOIN FETCH r.requester
            WHERE r.target.id = :targetId AND r.status = 'PENDING'
            ORDER BY r.createdAt DESC
            """)
    List<FollowRequest> findPendingIncoming(@Param("targetId") UUID targetId);

    @Query("""
            SELECT r FROM FollowRequest r JOIN FETCH r.target
            WHERE r.requester.id = :requesterId AND r.status = 'PENDING'
            ORDER BY r.createdAt DESC
            """)
    List<FollowRequest> findPendingOutgoing(@Param("requesterId") UUID requesterId);

    // Batch para discover: evita N+1 (una consulta para toda la pagina en
    // vez de una por usuario listado).
    @Query("""
            SELECT r.target.id FROM FollowRequest r
            WHERE r.requester.id = :requesterId AND r.status = 'PENDING' AND r.target.id IN :targetIds
            """)
    List<UUID> findPendingOutgoingTargetIdsAmong(@Param("requesterId") UUID requesterId, @Param("targetIds") List<UUID> targetIds);

    // Claims atomicos: mismo patron que AuthSessionRepository.claimForRotation
    // (Fase 1.5) -- un UPDATE con WHERE status = 'PENDING' solo puede tener
    // exito para UNA de dos requests concurrentes sobre la misma fila
    // (aceptar dos veces, aceptar+cancelar a la vez, etc). El caller debe
    // chequear el valor de retorno: 0 = alguien mas ya resolvio esta
    // solicitud entre la lectura de validacion y este UPDATE.
    @Modifying
    @Query("""
            UPDATE FollowRequest r SET r.status = 'ACCEPTED', r.respondedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.status = 'PENDING'
            """)
    int claimAccept(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE FollowRequest r SET r.status = 'REJECTED', r.respondedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.status = 'PENDING'
            """)
    int claimReject(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE FollowRequest r SET r.status = 'CANCELLED', r.respondedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.status = 'PENDING'
            """)
    int claimCancel(@Param("id") UUID id);
}
