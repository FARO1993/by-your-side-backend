package com.byyourside.backend.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Query("""
            SELECT c FROM Comment c
            JOIN FETCH c.author
            WHERE c.post.id = :postId
            AND c.status = 'VISIBLE'
            ORDER BY c.createdAt ASC
            """)
    List<Comment> findVisibleCommentsByPostId(@Param("postId") UUID postId);
}