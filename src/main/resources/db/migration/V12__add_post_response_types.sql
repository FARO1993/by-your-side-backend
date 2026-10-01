-- Backend Debt B1: evoluciona el soporte binario (post_supports) a una
-- respuesta tipada por usuario/post (post_responses). Se ALTERea la tabla
-- existente en vez de crear una tabla paralela y migrar filas -- conserva
-- id/created_at originales sin reescritura de PK, evita duplicar
-- temporalmente la fuente de verdad, y el UNIQUE(post_id, user_id) ya
-- existente sigue siendo exactamente la regla que necesita PostResponse:
-- una sola fila activa por usuario/post (cambiar de tipo es UPDATE de esa
-- misma fila, nunca una fila nueva).
ALTER TABLE post_supports RENAME TO post_responses;

-- Todo soporte historico binario equivale a "WITH_YOU" -- unico tipo que
-- existia conceptualmente antes de esta fase (el frontend hoy solo
-- persiste "Estoy con vos"; el resto de las reacciones son locales). Sin
-- CHECK constraint sobre el valor -- mismo criterio que notifications.type
-- y status_reactions.type (ver V1: un CHECK ademas del enum de Java es lo
-- que rompio antes cuando el enum crecio).
ALTER TABLE post_responses ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'WITH_YOU';
ALTER TABLE post_responses ALTER COLUMN type DROP DEFAULT;

-- updated_at nuevo: cambiar de tipo (UPDATE de la misma fila) debe poder
-- distinguirse de la creacion original. Para filas historicas no hay
-- "ultima actualizacion" real distinta de su creacion.
ALTER TABLE post_responses ADD COLUMN updated_at TIMESTAMP;
UPDATE post_responses SET updated_at = created_at;
ALTER TABLE post_responses ALTER COLUMN updated_at SET NOT NULL;

-- Critico: NotificationType.NEW_SUPPORT deja de existir en el enum de Java
-- (se reemplaza por NEW_POST_RESPONSE, ver BACKEND_ARCHITECTURE.md). Sin
-- este UPDATE, cualquier notificacion historica con type = 'NEW_SUPPORT'
-- rompe la deserializacion de @Enumerated(EnumType.STRING) al leerla (GET
-- /api/notifications tiraria IllegalArgumentException para cualquier
-- usuario con notificaciones de apoyo previas a esta fase).
UPDATE notifications SET type = 'NEW_POST_RESPONSE' WHERE type = 'NEW_SUPPORT';
