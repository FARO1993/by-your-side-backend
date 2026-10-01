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

// Una fila = una GENERACION de refresh token, no "una sesion" en si misma.
// La sesion (un login en un dispositivo dado) es la familia completa de filas
// que comparten familyId -- rotar el refresh token crea una fila nueva con el
// mismo familyId, nunca actualiza el hash de la fila existente, para poder
// detectar reuse de cualquier generacion anterior (ver AuthSessionService).
@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Estable a traves de todas las rotaciones de una misma sesion/dispositivo.
    // Se genera nuevo en cada login/register -- un login nuevo nunca reutiliza
    // la familia de otro dispositivo ya conectado.
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    // Hash SHA-256 del refresh token real -- el valor original nunca se
    // persiste, solo existe en memoria para devolverlo al cliente una vez.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    // null = esta generacion sigue siendo la vigente de su familia. No-null =
    // fue intercambiada exitosamente por una generacion nueva (camino sano);
    // si esta MISMA generacion vuelve a presentarse despues de esto, es reuse.
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    // Distinto de rotatedAt: revocacion explicita (logout, cambio/reset de
    // contrasena, o reuse detectado en cualquier generacion de la familia).
    // Una vez revocada, TODA la familia queda inutilizable sin importar el
    // estado individual de rotatedAt de cada fila.
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isRotated() {
        return rotatedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
