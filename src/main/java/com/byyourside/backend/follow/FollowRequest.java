package com.byyourside.backend.follow;

import com.byyourside.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// El TRAMITE de una solicitud de seguimiento a un perfil PRIVATE -- distinto
// de Follow, que es la relacion efectiva resultante (creada recien cuando
// esta solicitud se acepta). Ninguna fila se borra nunca: ACCEPTED/REJECTED/
// CANCELLED quedan como historial minimo, igual que el resto de los tokens
// de un solo uso de este esquema.
@Entity
@Table(name = "follow_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FollowRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private User target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FollowRequestStatus status = FollowRequestStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
