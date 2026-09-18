package com.byyourside.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByUserAIdAndUserBId(UUID userAId, UUID userBId);

    @Query("""
            SELECT c FROM Conversation c
            JOIN FETCH c.userA
            JOIN FETCH c.userB
            WHERE c.userA.id = :userId OR c.userB.id = :userId
            ORDER BY c.lastMessageAt DESC NULLS LAST
            """)
    List<Conversation> findByParticipant(@Param("userId") UUID userId);
}