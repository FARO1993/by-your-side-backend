package com.byyourside.backend.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByRole(UserRole role);

    Page<User> findByIdNotIn(Collection<UUID> excludedIds, Pageable pageable);

    // Backend Debt B4B.5: SELECT ... FOR UPDATE sobre la fila propia del
    // usuario -- usado por CompanionPreferenceService.replacePreferences
    // para serializar dos PATCH concurrentes del mismo usuario (ver ahi el
    // analisis completo de por que hace falta: sin este lock, dos
    // reemplazos de companion_preferences podrian intercalarse y dejar una
    // mezcla de ambos sets en vez de reemplazar por completo con uno). No
    // usado por ningun otro flujo -- no es un lock general de escritura de
    // User, es especifico de esta seccion critica.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
