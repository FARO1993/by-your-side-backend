package com.byyourside.backend.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    @Query("""
            SELECT p FROM Post p
            WHERE p.author.id IN :followedUserIds
            AND p.status = 'VISIBLE'
            AND p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
            ORDER BY p.createdAt DESC
            """)
    Page<Post> findFeedForUser(@Param("followedUserIds") List<UUID> followedUserIds, Pageable pageable);
}
