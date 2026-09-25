-- Fase 1.1: infraestructura de verificacion de email.
--
-- Los usuarios existentes nunca tuvieron oportunidad de verificar su correo
-- (la funcionalidad no existia), asi que se los considera verificados desde
-- ya: el DEFAULT TRUE se aplica a todas las filas existentes en el momento
-- de este ALTER TABLE. Recien despues cambiamos el DEFAULT a FALSE, asi que
-- eso solo afecta a las filas insertadas de aca en adelante (usuarios nuevos
-- arrancan sin verificar).
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN email_verified SET DEFAULT FALSE;

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP;
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL AND email_verified = TRUE;

-- Se guarda el hash (SHA-256) del token, nunca el valor real -- igual que un
-- password, si se filtra la tabla no alcanza para verificar cuentas ajenas.
-- used_at nullable cubre a la vez "fue consumido" y "cuando": NULL = todavia
-- valido para usar, no-NULL = ya consumido, no reutilizable.
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
