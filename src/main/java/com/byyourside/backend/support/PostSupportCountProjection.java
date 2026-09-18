package com.byyourside.backend.support;

import java.util.UUID;

// Proyeccion para traer conteos agrupados en una sola query (batch por
// pagina de feed), en vez de una query de COUNT por post.
public interface PostSupportCountProjection {
    UUID getPostId();
    long getSupportCount();
}