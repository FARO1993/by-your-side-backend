package com.byyourside.backend.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    // Fase 9.3: :followedUserIds ya viene construido (en PostService.getFeed)
    // a partir de filas REALES de `follows` -- es decir, ya son todos
    // followers efectivos/aceptados, sin importar si esa relacion se creo
    // por un follow inmediato (perfil PUBLIC) o por una FollowRequest
    // aceptada (perfil PRIVATE). Por eso esta query NO necesita mirar
    // profileVisibility del autor en absoluto: pertenecer a
    // :followedUserIds YA implica acceso, y el perfil PRIVATE de un autor
    // que me acepto no oculta sus posts PUBLIC/FOLLOWERS_ONLY del feed (a
    // diferencia de Fase 9.1/9.2, donde no existia el concepto de "aceptado"
    // y el perfil PRIVATE bloqueaba a cualquier tercero sin excepcion).
    // "propios" (a.id = :currentUserId) sigue siendo una rama separada: el
    // dueno ve TODO lo suyo (incluido PRIVATE), sin importar nada mas.
    // Fase 9.4: el NOT EXISTS de bloqueo aca es defensa en profundidad -- en
    // la practica, :followedUserIds ya nunca puede contener a alguien
    // bloqueado (BlockService.blockUser borra la fila `follows` en ambas
    // direcciones en el momento de bloquear, y FollowService.follow()
    // rechaza crear una nueva mientras el bloqueo siga activo), pero la
    // consigna de esta fase pide el filtro explicito en la query del feed
    // (no solo depender de esa invariante transitiva) para no depender
    // silenciosamente de que esa limpieza nunca tenga un bug.
    // Fase 9.5: el NOT EXISTS de UserMute es UNILATERAL (solo
    // :currentUserId como muter) -- a diferencia del de UserBlock, este no
    // tiene contraparte transitiva: mutear no toca `follows`, asi que este
    // es el UNICO lugar donde esa exclusion ocurre para el feed. El autor
    // sigue siendo un follower efectivo (isFollower, etc. sin cambios) --
    // mute nunca afecta el acceso directo a su perfil/posts, solo lo saca
    // de esta query agregada.
    @Query("""
            SELECT p FROM Post p
            JOIN FETCH p.author a
            WHERE a.id IN :followedUserIds
            AND p.status = 'VISIBLE'
            AND (
                a.id = :currentUserId
                OR p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
            )
            AND (p.anonymous = false OR a.id = :currentUserId)
            AND NOT EXISTS (
                SELECT 1 FROM UserBlock b
                WHERE (b.blocker.id = :currentUserId AND b.blocked.id = a.id)
                OR (b.blocker.id = a.id AND b.blocked.id = :currentUserId)
            )
            AND NOT EXISTS (
                SELECT 1 FROM UserMute m
                WHERE m.muter.id = :currentUserId AND m.muted.id = a.id
            )
            ORDER BY p.createdAt DESC
            """)
    Page<Post> findFeedForUser(@Param("followedUserIds") List<UUID> followedUserIds,
                               @Param("currentUserId") UUID currentUserId,
                               Pageable pageable);

    // Fase 9.3: a diferencia del feed (que ya parte de una lista de
    // followers efectivos), acá SI hace falta el gate de profileVisibility,
    // porque :canSeeFollowersOnly puede ser true para un perfil PUBLIC sin
    // que eso signifique nada especial (cualquiera ve lo PUBLIC de un perfil
    // PUBLIC, sea o no follower) -- lo que cambia en esta fase es que ese
    // mismo gate (perfil PUBLIC O follower efectivo) ahora TAMBIEN abre el
    // acceso completo a un perfil PRIVATE cuando :canSeeFollowersOnly es
    // true (es decir, cuando ya hay una fila real en `follows`, ver
    // PostService.getUserPosts). :isOwner sigue siendo la unica rama que
    // ademas incluye PRIVATE.
    @Query("""
            SELECT p FROM Post p
            JOIN FETCH p.author a
            WHERE a.id = :authorId
            AND p.status = 'VISIBLE'
            AND (p.anonymous = false OR :isOwner = true)
            AND (
                :isOwner = true
                OR (
                    (a.profileVisibility = 'PUBLIC' OR :canSeeFollowersOnly = true)
                    AND (
                        p.visibility = 'PUBLIC'
                        OR (p.visibility = 'FOLLOWERS_ONLY' AND :canSeeFollowersOnly = true)
                    )
                )
            )
            ORDER BY p.createdAt DESC
            """)
    Page<Post> findVisiblePostsByAuthor(@Param("authorId") UUID authorId,
                                        @Param("canSeeFollowersOnly") boolean canSeeFollowersOnly,
                                        @Param("isOwner") boolean isOwner,
                                        Pageable pageable);

    // V20: los anonimos de terceros nunca entran al feed de seguidores (ver
    // findFeedForUser) ni al perfil del autor (findVisiblePostsByAuthor) --
    // si lo hicieran, el anonimato seria debil (5 seguidores = facil de
    // adivinar). Viven en este espacio propio, visible para cualquier usuario
    // autenticado. Bloqueo bilateral y mute unilateral se aplican con el
    // autor REAL, igual que en el feed. Sin gate de profileVisibility a
    // proposito (ver PostAccessPolicy#canView). Sirve el indice parcial
    // idx_posts_anonymous_created_at (V20).
    @Query("""
            SELECT p FROM Post p
            JOIN FETCH p.author a
            WHERE p.anonymous = true
            AND p.status = 'VISIBLE'
            AND NOT EXISTS (
                SELECT 1 FROM UserBlock b
                WHERE (b.blocker.id = :currentUserId AND b.blocked.id = a.id)
                OR (b.blocker.id = a.id AND b.blocked.id = :currentUserId)
            )
            AND NOT EXISTS (
                SELECT 1 FROM UserMute m
                WHERE m.muter.id = :currentUserId AND m.muted.id = a.id
            )
            ORDER BY p.createdAt DESC
            """)
    Page<Post> findAnonymousFeed(@Param("currentUserId") UUID currentUserId, Pageable pageable);

    // Limite diario de anonimos: cuenta TODOS (tambien los borrados), asi
    // borrar y volver a publicar no esquiva el limite.
    long countByAuthorIdAndAnonymousTrueAndCreatedAtAfter(UUID authorId, Instant since);
}
