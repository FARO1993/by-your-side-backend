package com.byyourside.backend.follow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFollowingId(UUID followerId, UUID followingId);

    Optional<Follow> findByFollowerIdAndFollowingId(UUID followerId, UUID followingId);

    List<Follow> findByFollowerId(UUID followerId);

    List<Follow> findByFollowingId(UUID followingId);

    long countByFollowingId(UUID followingId);

    long countByFollowerId(UUID followerId);

    @Query("""
            SELECT f.following.id FROM Follow f
            WHERE f.follower.id = :followerId
            AND f.following.id IN :targetIds
            """)
    List<UUID> findFollowingIdsAmong(@Param("followerId") UUID followerId, @Param("targetIds") List<UUID> targetIds);
}
