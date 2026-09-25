-- Fase 1.2: reenvio de verificacion invalida los tokens pendientes anteriores
-- de un usuario en vez de dejarlos vivos, para no acumular tokens validos
-- sueltos. invalidated_at es distinto de used_at a proposito: used_at
-- significa "este token efectivamente verifico la cuenta", invalidated_at
-- significa "fue superado por un reenvio sin llegar a usarse" -- se
-- conservan ambos por separado, sin borrar filas, para no perder precision
-- de auditoria sobre que paso realmente con cada token.
ALTER TABLE email_verification_tokens ADD COLUMN invalidated_at TIMESTAMP;
