-- Distraerme "jugar acompañado": salas privadas entre dos personas.
--
-- Una sala nace como invitacion (INVITED), pasa a ACTIVE cuando la persona
-- invitada acepta y termina en ENDED (con end_reason: DECLINED, CANCELLED,
-- LEFT, EXPIRED o UNAVAILABLE). No hay ganadores ni puntajes: el backend no
-- conoce las reglas de cada juego, solo ordena y reparte las jugadas.
--
-- `seed` es compartida para que ambos clientes armen el mismo tablero
-- (mismo mazo de Memoria, mismo corte de Puzzle). Cabe en un int de JS.
CREATE TABLE game_rooms (
    id UUID PRIMARY KEY,
    game VARCHAR(20) NOT NULL,
    host_id UUID NOT NULL REFERENCES users(id),
    guest_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    end_reason VARCHAR(20),
    seed BIGINT NOT NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    last_activity_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_game_rooms_host_not_guest CHECK (host_id <> guest_id)
);

CREATE INDEX idx_game_rooms_host_status ON game_rooms(host_id, status);
CREATE INDEX idx_game_rooms_guest_status ON game_rooms(guest_id, status);

-- Una sola invitacion pendiente por par (host -> guest) a la vez: volver a
-- invitar reutiliza o reemplaza la anterior en vez de apilar invitaciones.
CREATE UNIQUE INDEX idx_game_rooms_one_invite_per_pair
    ON game_rooms(host_id, guest_id)
    WHERE status = 'INVITED';

-- Jugadas en orden (seq empieza en 1 por sala). Sirven para que un cliente
-- que se reconecta recupere la partida reproduciendolas.
CREATE TABLE game_room_events (
    id BIGSERIAL PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES game_rooms(id),
    seq INTEGER NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_game_room_events_room_seq UNIQUE (room_id, seq)
);
