package com.byyourside.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenHash(String tokenHash);

    // Claim atomico para rotacion: un UPDATE con WHERE rotated_at/revoked_at
    // IS NULL solo puede tener exito para UNA de dos requests concurrentes
    // sobre la misma fila -- PostgreSQL serializa la evaluacion del WHERE y
    // la escritura de cada fila individual, asi que no hace falta locking
    // explicito (@Version, SELECT ... FOR UPDATE) para evitar que dos
    // refresh simultaneos sobre el mismo token produzcan dos hijos validos.
    // El caller debe chequear el valor de retorno: 0 = perdio la carrera (o
    // alguien ya la habia rotado/revocado justo antes), no debe crear una
    // sesion hija en ese caso.
    @Modifying
    @Query("""
            UPDATE AuthSession s SET s.rotatedAt = CURRENT_TIMESTAMP, s.lastUsedAt = CURRENT_TIMESTAMP
            WHERE s.id = :id AND s.rotatedAt IS NULL AND s.revokedAt IS NULL
            """)
    int claimForRotation(@Param("id") UUID id);

    // Revoca (no borra) TODAS las generaciones de una familia -- usado ante
    // reuse detectado y en logout. Idempotente: una familia ya revocada no
    // vuelve a tocarse.
    @Modifying
    @Query("""
            UPDATE AuthSession s SET s.revokedAt = CURRENT_TIMESTAMP
            WHERE s.familyId = :familyId AND s.revokedAt IS NULL
            """)
    void revokeFamily(@Param("familyId") UUID familyId);

    // Revoca todas las sesiones (todas las familias) de un usuario -- usado
    // por change-password y reset-password, que por decision de producto
    // cierran todas las sesiones activas.
    @Modifying
    @Query("""
            UPDATE AuthSession s SET s.revokedAt = CURRENT_TIMESTAMP
            WHERE s.user.id = :userId AND s.revokedAt IS NULL
            """)
    void revokeAllForUser(@Param("userId") UUID userId);
}
