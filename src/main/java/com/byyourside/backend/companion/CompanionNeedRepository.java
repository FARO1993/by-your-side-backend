package com.byyourside.backend.companion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CompanionNeedRepository extends JpaRepository<CompanionNeed, UUID> {

    void deleteByUserId(UUID userId);

    Optional<CompanionNeed> findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);
}
