package com.byyourside.backend.companion;

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
@Table(name = "companion_needs", uniqueConstraints = {
        @UniqueConstraint(columnNames = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanionNeed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NeedType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Backend Debt B4B.1: vence a las 2hs -- mas corto que Offering (6hs,
    // B4B.2) y que Status (24hs): "necesito compañia ahora" es la
    // declaracion de mayor urgencia/menor duracion del dominio Companion.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
