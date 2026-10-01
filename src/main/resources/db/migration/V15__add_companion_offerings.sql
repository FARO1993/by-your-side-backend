-- Backend Debt B4B.2: nueva tabla del dominio Companion (Offering) --
-- "como puedo acompañar ahora". Coexiste TEMPORALMENTE con `availabilities`
-- (que sigue siendo la fuente de verdad exclusiva del contrato LEGACY
-- /api/availability/**) durante esta transicion -- ver diseño B4A y
-- BACKEND_ARCHITECTURE.md § Companion Offering para el wording exacto de
-- source-of-truth. Ningun codigo de este PR lee ambas tablas para responder
-- la misma operacion. B4B.3 unifica ambas fuentes, migra ChatService y
-- /api/availability/** a adapters sobre este dominio, y recien ahi retira
-- `availabilities`.
--
-- UNIQUE(user_id): igual que companion_needs (V14) -- B4B.2 no conserva
-- historial, el Offering anterior se reemplaza al setear uno nuevo. Ultima
-- defensa a nivel DB contra dos PUT concurrentes insertando dos filas para
-- el mismo usuario -- ver CompanionOfferingWriter.replace. Una fila
-- expirada puede seguir fisicamente en la tabla hasta que el usuario
-- vuelva a setear/cancelar -- el UNIQUE no exige limpieza, solo que como
-- mucho exista UNA fila por usuario en cualquier momento.
--
-- INDEX(type, expires_at): a diferencia de companion_needs (V14, que NO
-- tiene ninguna query real fuera de user_id), esta tabla SI tiene una query
-- real que filtra por type + expires_at sin pasar por user_id
-- (CompanionOfferingRepository.findRandomCandidatesByType) -- justifica un
-- indice propio. No se duplica un indice simple sobre user_id -- el
-- UNIQUE(user_id) ya lo crea.
--
-- Sin CHECK constraint sobre `type` -- mismo criterio ya establecido desde
-- V1 para columnas VARCHAR respaldadas por enum.
CREATE TABLE companion_offerings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_companion_offerings_user_id UNIQUE (user_id)
);

CREATE INDEX idx_companion_offerings_type_expires_at ON companion_offerings(type, expires_at);
