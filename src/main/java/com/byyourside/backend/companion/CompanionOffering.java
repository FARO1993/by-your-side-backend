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
@Table(name = "companion_offerings", uniqueConstraints = {
        @UniqueConstraint(columnNames = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanionOffering {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferingType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Backend Debt B4B.2: vence a las 6hs -- mismo plazo que Availability
    // legacy, mas largo que Need (2hs, B4B.1): "como puedo acompañar ahora"
    // es una declaracion de disponibilidad de mediano plazo, no tan
    // urgente como un Need.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
