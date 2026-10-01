-- Fase 9.4: bloqueo de usuario a usuario. Direccional en la tabla (blocker ->
-- blocked) pero el ACCESO se trata como bilateral en toda la aplicacion via
-- BlockPolicy.isBlockedBetween (A bloqueo a B O B bloqueo a A) -- un bloqueo
-- de cualquiera de los dos lados corta la relacion para ambos.
--
-- Desbloquear es un DELETE liso de esta fila -- a diferencia de FollowRequest
-- (que conserva PENDING/ACCEPTED/REJECTED/CANCELLED como historial minimo),
-- un bloqueo revertido no tiene valor de producto en conservarse como
-- historial: moderacion ya tiene su propio rastro independiente via Report.
CREATE TABLE user_blocks (
    id UUID PRIMARY KEY,
    blocker_id UUID NOT NULL REFERENCES users(id),
    blocked_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    -- Constraint estructural (no un CHECK sobre valores de enum -- ver V1
    -- para el antecedente de por que esos se evitan en este esquema): nadie
    -- puede bloquearse a si mismo.
    CONSTRAINT chk_user_blocks_blocker_not_blocked CHECK (blocker_id <> blocked_id),
    CONSTRAINT uq_user_blocks_pair UNIQUE (blocker_id, blocked_id)
);

CREATE INDEX idx_user_blocks_blocker_id ON user_blocks(blocker_id);
CREATE INDEX idx_user_blocks_blocked_id ON user_blocks(blocked_id);
