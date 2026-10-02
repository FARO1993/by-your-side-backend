package com.byyourside.backend.game;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GameRoomEventRepository extends JpaRepository<GameRoomEvent, Long> {

    @Query("""
            SELECT e FROM GameRoomEvent e JOIN FETCH e.actor
            WHERE e.room.id = :roomId AND e.seq > :after
            ORDER BY e.seq ASC
            """)
    List<GameRoomEvent> findAfter(@Param("roomId") UUID roomId, @Param("after") int after, Pageable pageable);

    // Historia de un juego persistente entre dos personas (hoy, el Jardin):
    // las jugadas de sus salas anteriores de ese juego, en orden.
    @Query("""
            SELECT e FROM GameRoomEvent e JOIN FETCH e.actor JOIN FETCH e.room r
            WHERE r.game = :game AND r.id <> :roomId AND r.createdAt < :before
              AND ((r.host.id = :a AND r.guest.id = :b) OR (r.host.id = :b AND r.guest.id = :a))
            ORDER BY r.createdAt ASC, r.id ASC, e.seq ASC
            """)
    List<GameRoomEvent> findHistory(@Param("game") GameType game,
                                    @Param("roomId") UUID roomId,
                                    @Param("before") Instant before,
                                    @Param("a") UUID a,
                                    @Param("b") UUID b,
                                    Pageable pageable);
}
