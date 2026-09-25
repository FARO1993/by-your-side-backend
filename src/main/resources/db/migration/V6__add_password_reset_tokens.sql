-- Fase 1.3: recuperacion de contrasena. Tabla independiente de
-- email_verification_tokens (aunque la forma es casi identica) porque
-- conceptualmente son dos credenciales de un solo uso distintas, con ciclos
-- de vida propios -- mezclarlas en una tabla compartida acoplaria dos flujos
-- de seguridad que conviene poder evolucionar por separado (ej. expiracion
-- mas corta acá, revocacion masiva ante un incidente, etc).
--
-- Mismo patron que email_verification_tokens: se guarda el hash (SHA-256)
-- del token, nunca el valor real. used_at nullable cubre "fue consumido" y
-- "cuando" (NULL = todavia valido, no-NULL = ya consumido). invalidated_at
-- se agrega desde el arranque (a diferencia de V4, que lo sumo recien en V5)
-- porque para password reset la invalidacion por reenvio es un requisito
-- desde el dia uno, no una mejora posterior.
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    invalidated_at TIMESTAMP
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
