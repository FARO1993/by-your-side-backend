-- Fase 1.5: sesiones persistidas para refresh token rotation.
--
-- Cada login/register crea una "familia" (family_id) que agrupa todas las
-- generaciones de un mismo refresh token a medida que se rota. Un dispositivo
-- nuevo (PC, celular) siempre arranca una familia propia -- un login nuevo no
-- cierra las sesiones de otros dispositivos. Esto es necesario para poder
-- detectar reuse: si vuelve a presentarse un hash que ya fue rotado
-- (rotated_at no nulo), se revoca toda la familia, inutilizando cualquier
-- token descendiente, sin importar cuantos saltos de rotacion haya habido
-- desde entonces (R1 -> R2 -> R3, reaparece R1 => se revoca toda la familia,
-- R3 incluido).
--
-- Mismo patron que email_verification_tokens/password_reset_tokens: se
-- guarda unicamente el hash SHA-256 del refresh token, nunca el valor real.
-- rotated_at != revoked_at a proposito, mismo criterio de auditoria que
-- invalidated_at en fases anteriores: rotated_at significa "este token fue
-- exitosamente intercambiado por uno nuevo" (camino sano, equivalente al
-- "usedAt" de un token de un solo uso); revoked_at significa "invalidado
-- explicitamente" (logout, cambio/reset de contrasena, o reuse detectado en
-- esta o cualquier otra generacion de la misma familia). Ninguna fila se
-- borra nunca -- son la unica fuente de verdad para auditar que paso con
-- cada sesion.
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_family_id ON auth_sessions(family_id);
