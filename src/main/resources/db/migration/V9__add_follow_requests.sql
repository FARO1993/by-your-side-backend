-- Fase 9.3: follow requests para perfiles PRIVATE. Un perfil PUBLIC sigue
-- generando un Follow inmediato (tabla follows, sin cambios); un perfil
-- PRIVATE genera en cambio una fila PENDING aca, que el dueno del perfil
-- debe aceptar antes de que exista una fila real en `follows`.
--
-- Estados: PENDING, ACCEPTED, REJECTED, CANCELLED. A proposito NO se borra
-- ninguna fila al resolver la solicitud (ni al aceptar, ni al rechazar/
-- cancelar) -- se conserva como historial minimo, igual que el resto de
-- los flujos de un solo uso de este esquema (email verification, password
-- reset, auth sessions). "ACCEPTED" no reemplaza la necesidad de la fila
-- real en `follows`: son dos conceptos relacionados pero distintos --
-- FollowRequest es el TRAMITE, Follow es la RELACION efectiva resultante.
CREATE TABLE follow_requests (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES users(id),
    target_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    -- Constraint estructural (no un CHECK sobre valores de enum -- ver V1
    -- para el antecedente de por que esos se evitan en este esquema): nadie
    -- puede enviarse una solicitud de seguimiento a si mismo.
    CONSTRAINT chk_follow_requests_requester_not_target CHECK (requester_id <> target_id)
);

CREATE INDEX idx_follow_requests_requester_id ON follow_requests(requester_id);
CREATE INDEX idx_follow_requests_target_id ON follow_requests(target_id);

-- Solo puede existir UNA solicitud PENDING activa por par (requester,
-- target) a la vez -- indice UNICO PARCIAL (Postgres), a proposito distinto
-- de un UNIQUE constraint comun: permite historial ilimitado de filas
-- ACCEPTED/REJECTED/CANCELLED para el mismo par (ej. rechazar una solicitud
-- y que el requester pueda volver a pedir mas adelante, sin chocar con la
-- fila vieja ya resuelta). Tambien es la defensa contra la carrera de dos
-- POST /api/follows/{id} concurrentes intentando crear la misma solicitud
-- PENDING dos veces.
CREATE UNIQUE INDEX idx_follow_requests_one_pending_per_pair
    ON follow_requests(requester_id, target_id)
    WHERE status = 'PENDING';
