package com.byyourside.backend.postresponse;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostResponseRepository extends JpaRepository<PostResponse, UUID> {

    Optional<PostResponse> findByPostIdAndUserId(UUID postId, UUID userId);

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    // Batch de conteos para toda una pagina de posts (feed / posts-by-user /
    // el propio detail, con una lista de un solo elemento) -- una sola query
    // agregada con SUM/CASE, nunca una query de COUNT por post ni carga de
    // filas completas en memoria. El agrupamiento PRESENCE/LISTENING replica
    // PostResponseType.isPresence()/isListening() -- si ese enum cambia,
    // esta query debe actualizarse junto con el (ver tests de counts).
    @Query("""
            SELECT r.post.id AS postId,
                   SUM(CASE WHEN r.type IN ('WITH_YOU', 'NOT_ALONE', 'HUG') THEN 1L ELSE 0L END) AS presenceCount,
                   SUM(CASE WHEN r.type IN ('READING', 'TELL_ME_MORE', 'LISTENING') THEN 1L ELSE 0L END) AS listeningCount
            FROM PostResponse r
            WHERE r.post.id IN :postIds
            GROUP BY r.post.id
            """)
    List<PostResponseCountProjection> countGroupedByPostIds(@Param("postIds") List<UUID> postIds);

    // Batch de "mi propia respuesta" para toda una pagina -- una sola query,
    // nunca un findByPostIdAndUserId por post. Devuelve las entidades (no
    // una proyeccion con el enum) porque r.getPost().getId() no dispara una
    // query adicional sobre un proxy LAZY (Hibernate resuelve el id del
    // proxy sin inicializarlo), y evita cualquier ambiguedad de mapeo de
    // enum vs. String en una interface projection.
    @Query("SELECT r FROM PostResponse r WHERE r.user.id = :userId AND r.post.id IN :postIds")
    List<PostResponse> findByUserIdAndPostIds(@Param("userId") UUID userId, @Param("postIds") List<UUID> postIds);
}
