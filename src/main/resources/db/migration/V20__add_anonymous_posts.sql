-- Posts anonimos: el autor SIEMPRE queda guardado (author_id sigue siendo
-- NOT NULL) -- "anonimo" significa que la API no lo expone a terceros, no
-- que no exista. Asi siguen funcionando bloqueos, silencios, el limite
-- diario y la moderacion (que ve el autor solo al revisar un reporte).
--
-- Los anonimos no aparecen en el feed de seguidores ni en el perfil del
-- autor: viven en su propio espacio (GET /api/posts/anonymous). El indice
-- parcial sirve exactamente esa query (solo anonimos visibles, por fecha).
ALTER TABLE posts ADD COLUMN anonymous BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_posts_anonymous_created_at
    ON posts (created_at DESC)
    WHERE anonymous = true AND status = 'VISIBLE';
