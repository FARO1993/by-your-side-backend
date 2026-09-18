package com.byyourside.backend.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.sender
            WHERE m.conversation.id = :conversationId
            ORDER BY m.createdAt ASC
            """)
    Page<Message> findByConversationId(@Param("conversationId") UUID conversationId, Pageable pageable);

    @Modifying
    @Query("""
            UPDATE Message m SET m.read = true
            WHERE m.conversation.id = :conversationId
            AND m.sender.id != :readerId
            AND m.read = false
            """)
    void markAsRead(@Param("conversationId") UUID conversationId, @Param("readerId") UUID readerId);

    @Query("""
            SELECT m.conversation.id AS conversationId, COUNT(m) AS unreadCount FROM Message m
            WHERE m.conversation.id IN :conversationIds
            AND m.sender.id != :userId
            AND m.read = false
            GROUP BY m.conversation.id
            """)
    List<UnreadCountProjection> countUnreadGroupedByConversation(@Param("conversationIds") List<UUID> conversationIds,
                                                                 @Param("userId") UUID userId);
}