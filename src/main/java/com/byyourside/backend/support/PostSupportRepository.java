package com.byyourside.backend.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostSupportRepository extends JpaRepository<PostSupport, UUID> {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    Optional<PostSupport> findByPostIdAndUserId(UUID postId, UUID userId);

    long countByPostId(UUID postId);

    @Query("""
            SELECT s.post.id AS postId, COUNT(s) AS supportCount FROM PostSupport s
            WHERE s.post.id IN :postIds
            GROUP BY s.post.id
            """)
    List<PostSupportCountProjection> countGroupedByPostIds(@Param("postIds") List<UUID> postIds);

    @Query("SELECT s.post.id FROM PostSupport s WHERE s.user.id = :userId AND s.post.id IN :postIds")
    List<UUID> findSupportedPostIds(@Param("userId") UUID userId, @Param("postIds") List<UUID> postIds);
}