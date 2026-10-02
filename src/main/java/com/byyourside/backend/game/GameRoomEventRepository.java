package com.byyourside.backend.game;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GameRoomEventRepository extends JpaRepository<GameRoomEvent, Long> {

    @Query("""
            SELECT e FROM GameRoomEvent e JOIN FETCH e.actor
            WHERE e.room.id = :roomId AND e.seq > :after
            ORDER BY e.seq ASC
            """)
    List<GameRoomEvent> findAfter(@Param("roomId") UUID roomId, @Param("after") int after, Pageable pageable);
}
