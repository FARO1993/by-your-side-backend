package com.byyourside.backend.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    // Fase 9.1: el perfil PRIVATE del autor domina sobre PostVisibility para
    // terceros -- por eso "propios" (a.id = :currentUserId) es una rama
    // separada que ignora tanto profileVisibility como post.visibility (el
    // dueno ve TODO lo suyo, VISIBLE, sin importar nada mas), mientras que la
    // rama de terceros exige ademas a.profileVisibility = 'PUBLIC'. Sin esta
    // rama propia, los posts PRIVATE del propio usuario no aparecerian en su
    // feed (bug real detectado al escribir los tests de esta fase).
    @Query("""
            SELECT p FROM Post p
            JOIN FETCH p.author a
            WHERE a.id IN :followedUserIds
            AND p.status = 'VISIBLE'
            AND (
                a.id = :currentUserId
                OR (a.profileVisibility = 'PUBLIC' AND p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY'))
            )
            ORDER BY p.createdAt DESC
            """)
    Page<Post> findFeedForUser(@Param("followedUserIds") List<UUID> followedUserIds,
                               @Param("currentUserId") UUID currentUserId,
                               Pageable pageable);

    // Mismo criterio que findFeedForUser: :isOwner ignora profileVisibility
    // del autor (uno mismo siempre ve sus propios posts), cualquier otro
    // viewer requiere ademas a.profileVisibility = 'PUBLIC' -- si el perfil
    // es PRIVATE y el viewer no es el dueno, ninguna fila matchea nunca,
    // sin importar canSeeFollowersOnly.
    @Query("""
            SELECT p FROM Post p
            JOIN FETCH p.author a
            WHERE a.id = :authorId
            AND p.status = 'VISIBLE'
            AND (
                :isOwner = true
                OR (
                    a.profileVisibility = 'PUBLIC'
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
}