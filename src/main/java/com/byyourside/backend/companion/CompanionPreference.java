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

// Backend Debt B4B.5: dato ESTABLE de perfil -- "como suelo estar para
// otros". A diferencia de CompanionNeed/CompanionOffering, no tiene
// expiresAt (no es momentaneo) ni UNIQUE(user_id) solo (un usuario puede
// tener 0-3 filas simultaneas, una por type). Nunca se infiere de
// Need/Offering activos, ni los sincroniza -- son tres conceptos
// completamente independientes.
//
// createdAt: se incluye pese a no participar del orden de respuesta
// (eso lo define el orden de declaracion del enum, ver
// CompanionPreferenceService) porque es una convencion sin excepciones en
// este proyecto -- las 18 entidades existentes al momento de esta auditoria
// lo tienen.
@Entity
@Table(name = "companion_preferences", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanionPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanionPreferenceType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
