package com.byyourside.backend.mute;

import com.byyourside.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// Unilateral a proposito: A mutea a B no implica nada sobre si B mutea a A.
// A diferencia de UserBlock (Fase 9.4, ACCESO consultado bilateralmente via
// BlockPolicy), el ACCESO nunca se consulta bilateralmente aca -- todas las
// queries que filtran por mute (feed, discover, status, availability) solo
// miran una direccion: muter -> muted. No hay campo de estado: dejar de
// silenciar es borrar esta fila, mismo criterio que UserBlock (ver V11).
@Entity
@Table(name = "user_mutes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"muter_id", "muted_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "muter_id", nullable = false)
    private User muter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "muted_id", nullable = false)
    private User muted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
