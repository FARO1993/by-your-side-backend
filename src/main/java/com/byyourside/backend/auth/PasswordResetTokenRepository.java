package com.byyourside.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    Optional<PasswordResetToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    // Invalida (no borra) los tokens todavia pendientes de un usuario --
    // usado por forgot-password antes de emitir uno nuevo.
    @Modifying
    @Query("""
            UPDATE PasswordResetToken t SET t.invalidatedAt = CURRENT_TIMESTAMP
            WHERE t.user.id = :userId AND t.usedAt IS NULL AND t.invalidatedAt IS NULL
            """)
    void invalidatePendingTokensForUser(@Param("userId") UUID userId);
}
