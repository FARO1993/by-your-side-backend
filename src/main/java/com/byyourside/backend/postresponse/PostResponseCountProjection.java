package com.byyourside.backend.postresponse;

import java.util.UUID;

// Proyeccion para traer los dos conteos agrupados en una sola query (batch
// por pagina de feed/posts-by-user), en vez de una query de COUNT por post
// ni de cargar todas las PostResponse en memoria para sumarlas a mano (ver
// PostResponseRepository.countGroupedByPostIds).
public interface PostResponseCountProjection {
    UUID getPostId();
    long getPresenceCount();
    long getListeningCount();
}
