package com.byyourside.backend.auth;

import com.byyourside.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Hash SHA-256 del token real -- el valor original nunca se persiste.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // null = todavia no consumido. Distinto de null = consumido en ese momento,
    // no reutilizable.
    @Column(name = "used_at")
    private Instant usedAt;

    // Distinto de usedAt a proposito: usedAt significa "este token efectivamente
    // cambio la contrasena"; invalidatedAt significa "fue superado por una
    // solicitud de forgot-password posterior sin llegar a usarse". Se conservan
    // ambos por separado para no perder precision de auditoria -- ninguno de
    // los dos se borra.
    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isInvalidated() {
        return invalidatedAt != null;
    }
}
