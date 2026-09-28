-- Backend Debt B4B.1: primera tabla del nuevo dominio Companion (Need),
-- que reemplaza gradualmente a `availabilities`/CompanionIntent (ver
-- diseño B4A).
--
-- UNIQUE(user_id): B4B.1 no conserva historial -- el Need anterior se
-- reemplaza, nunca se acumula (mismo criterio de "una fila activa por
-- usuario" que Availability, pero aca ademas garantizado a nivel DB, no
-- solo por el service). Esta constraint es la ultima defensa contra dos
-- PUT concurrentes insertando dos filas para el mismo usuario -- ver
-- CompanionNeedWriter.replace.
--
-- Sin indices adicionales: UNIQUE(user_id) ya crea el indice que la unica
-- query real necesita (lookup por user_id en
-- findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc). Un indice
-- separado sobre expires_at, o uno compuesto (user_id, expires_at), serian
-- redundantes hoy -- no hay ninguna query que filtre solo por expires_at
-- (no hay scheduler de limpieza), y como a lo sumo existe una fila por
-- usuario, el indice UNIQUE ya resuelve el lookup en tiempo constante.
--
-- Sin CHECK constraint sobre `type` -- mismo criterio ya establecido desde
-- V1 para columnas VARCHAR respaldadas por enum (evita el incidente
-- historico de tener que migrar un CHECK cada vez que el enum crece).
CREATE TABLE companion_needs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_companion_needs_user_id UNIQUE (user_id)
);
