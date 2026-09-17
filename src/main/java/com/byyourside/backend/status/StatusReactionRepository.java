package com.byyourside.backend.status;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusReactionRepository extends JpaRepository<StatusReaction, UUID> {

    Optional<StatusReaction> findByStatusIdAndActorId(UUID statusId, UUID actorId);

    long countByStatusId(UUID statusId);

    @Query("""
            SELECT r.status.id AS statusId, COUNT(r) AS reactionCount FROM StatusReaction r
            WHERE r.status.id IN :statusIds
            GROUP BY r.status.id
            """)
    List<StatusReactionCountProjection> countGroupedByStatusIds(@Param("statusIds") List<UUID> statusIds);

    @Query("SELECT r FROM StatusReaction r WHERE r.actor.id = :actorId AND r.status.id IN :statusIds")
    List<StatusReaction> findByActorIdAndStatusIds(@Param("actorId") UUID actorId, @Param("statusIds") List<UUID> statusIds);
}