package com.byyourside.backend.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByRole(UserRole role);

    // Backend Debt B5.1: Discover server-side. TODA la exclusion (usuario
    // actual, cuentas no ACTIVE, block bilateral, mute unilateral y, solo
    // en browse, followed) vive en la query como NOT EXISTS -- nunca una
    // lista NOT IN armada en Java, asi el costo no crece con la cantidad de
    // follows/blocks/mutes del viewer. El role NO participa: es
    // autorizacion, no identidad social (un MODERATOR/ADMIN ACTIVE es
    // discoverable igual que un USER). Email y bio nunca se buscan.
    //
    // El ORDER BY va en la query (el Pageable llega SIN Sort a proposito):
    // sin orden total, Postgres puede repetir o saltear filas entre paginas.
    // El desempate por id hace el orden determinista.
    String DISCOVER_BASE_FILTERS = """
            u.id <> :me
            AND u.status = :activeStatus
            AND NOT EXISTS (
                SELECT 1 FROM UserBlock b
                WHERE (b.blocker.id = :me AND b.blocked.id = u.id)
                OR (b.blocker.id = u.id AND b.blocked.id = :me)
            )
            AND NOT EXISTS (
                SELECT 1 FROM UserMute m
                WHERE m.muter.id = :me AND m.muted.id = u.id
            )
            """;

    // Solo browse: en search los followed SI aparecen (con FOLLOWING).
    String DISCOVER_NOT_FOLLOWED = """
            AND NOT EXISTS (
                SELECT 1 FROM Follow f
                WHERE f.follower.id = :me AND f.following.id = u.id
            )
            """;

    // :pattern llega ya normalizado, en minusculas y con \, % y _ escapados
    // (ver UserService.toContainsPattern).
    String DISCOVER_NAME_MATCH = """
            AND (LOWER(u.username) LIKE :pattern ESCAPE '\\'
                 OR LOWER(u.displayName) LIKE :pattern ESCAPE '\\')
            """;

    String DISCOVER_ORDER = " ORDER BY LOWER(COALESCE(u.displayName, u.username)) ASC, u.id ASC";

    @Query(value = "SELECT u FROM User u WHERE " + DISCOVER_BASE_FILTERS + DISCOVER_NOT_FOLLOWED + DISCOVER_ORDER,
            countQuery = "SELECT COUNT(u) FROM User u WHERE " + DISCOVER_BASE_FILTERS + DISCOVER_NOT_FOLLOWED)
    Page<User> browseDiscoverable(@Param("me") UUID me,
                                  @Param("activeStatus") UserStatus activeStatus,
                                  Pageable pageable);

    @Query(value = "SELECT u FROM User u WHERE " + DISCOVER_BASE_FILTERS + DISCOVER_NAME_MATCH + DISCOVER_ORDER,
            countQuery = "SELECT COUNT(u) FROM User u WHERE " + DISCOVER_BASE_FILTERS + DISCOVER_NAME_MATCH)
    Page<User> searchDiscoverable(@Param("me") UUID me,
                                  @Param("activeStatus") UserStatus activeStatus,
                                  @Param("pattern") String pattern,
                                  Pageable pageable);

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
