package com.byyourside.backend.game;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRoomRepository extends JpaRepository<GameRoom, UUID> {

    // Serializa las escrituras sobre una misma sala (aceptar, salir, sumar
    // jugadas): asi `seq` nunca se repite ni se saltea aunque las dos
    // personas jueguen al mismo tiempo.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GameRoom r WHERE r.id = :id")
    Optional<GameRoom> findByIdForUpdate(@Param("id") UUID id);

    Optional<GameRoom> findByHostIdAndGuestIdAndStatus(UUID hostId, UUID guestId, GameRoomStatus status);

    long countByHostIdAndStatus(UUID hostId, GameRoomStatus status);

    @Query("""
            SELECT r FROM GameRoom r
            JOIN FETCH r.host JOIN FETCH r.guest
            WHERE (r.host.id = :userId OR r.guest.id = :userId) AND r.status IN :statuses
            ORDER BY r.createdAt DESC
            """)
    List<GameRoom> findByParticipantAndStatusIn(@Param("userId") UUID userId,
                                                @Param("statuses") Collection<GameRoomStatus> statuses);

    @Query("""
            SELECT r FROM GameRoom r
            WHERE ((r.host.id = :a AND r.guest.id = :b) OR (r.host.id = :b AND r.guest.id = :a))
              AND r.status IN :statuses
            """)
    List<GameRoom> findBetweenAndStatusIn(@Param("a") UUID a,
                                          @Param("b") UUID b,
                                          @Param("statuses") Collection<GameRoomStatus> statuses);
}
