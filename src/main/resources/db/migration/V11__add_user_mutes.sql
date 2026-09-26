-- Fase 9.5: silenciar (mute) de usuario a usuario. A diferencia de
-- user_blocks (Fase 9.4, bilateral en efecto via BlockPolicy), esta
-- relacion es estrictamente UNILATERAL: A mutea a B no implica nada sobre
-- si B mutea a A, y el ACCESO nunca se consulta de forma bilateral -- solo
-- el propio muter deja de ver contenido del muted en superficies agregadas
-- (feed, discover, status/presence, disponibilidad/companion). No corta
-- Follow/FollowRequest, no afecta perfil/posts/chat/interaccion directa, y
-- el muted nunca se entera (no existe forma de consultar "quien me muteo"
-- desde la aplicacion).
--
-- Dejar de silenciar es un DELETE liso de esta fila -- mismo criterio que
-- user_blocks: no hay valor de producto en conservar historial de mutes.
CREATE TABLE user_mutes (
    id UUID PRIMARY KEY,
    muter_id UUID NOT NULL REFERENCES users(id),
    muted_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    -- Constraint estructural (mismo criterio que V10): nadie puede
    -- silenciarse a si mismo.
    CONSTRAINT chk_user_mutes_muter_not_muted CHECK (muter_id <> muted_id),
    CONSTRAINT uq_user_mutes_pair UNIQUE (muter_id, muted_id)
);

CREATE INDEX idx_user_mutes_muter_id ON user_mutes(muter_id);
CREATE INDEX idx_user_mutes_muted_id ON user_mutes(muted_id);
