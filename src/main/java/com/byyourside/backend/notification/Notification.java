package com.byyourside.backend.notification;

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
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Quien recibe la notificacion (no quien la genera).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    // Quien genero la accion (el nuevo seguidor, quien comento, quien envio apoyo).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    // Referencia opcional al post relacionado (comentario o respuesta a un
    // post). Null para NEW_FOLLOWER, que no esta atado a ningun post.
    @Column(name = "post_id")
    private UUID postId;

    // Backend Debt B3: referencia opcional al status relacionado
    // (NEW_STATUS_REACTION). Sin FK -- mismo criterio que postId (ver mas
    // abajo y V13): Status nunca se borra (solo expira, queda en DB), asi
    // que ni siquiera hay un escenario real de "recurso borrado" que
    // gestionar, pero se mantiene el mismo patron de columna UUID simple
    // por consistencia con postId.
    @Column(name = "status_id")
    private UUID statusId;

    // Backend Debt B3: referencia opcional al tramite de FollowRequest
    // relacionado (FOLLOW_REQUEST_RECEIVED, y FOLLOW_REQUEST_ACCEPTED para
    // contexto). Sin FK, mismo criterio que postId/statusId -- FollowRequest
    // tampoco se borra nunca (ACCEPTED/REJECTED/CANCELLED quedan como
    // historial), asi que puede apuntar a un tramite ya resuelto (ver
    // BACKEND_ARCHITECTURE.md): el frontend debe manejar ese caso via la
    // respuesta normal de FollowRequestService (409 si ya no esta PENDING),
    // no vía un estado duplicado aca.
    @Column(name = "follow_request_id")
    private UUID followRequestId;

    @Column(nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}