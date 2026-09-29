-- Backend Debt B4B.5: "como suelo estar para otros" -- dato ESTABLE de
-- perfil, sin relacion con Need/Offering (dominios independientes, nunca
-- sincronizados). A diferencia de companion_needs/companion_offerings
-- (V14/V15, UNIQUE(user_id) -- a lo sumo una fila por usuario), un usuario
-- puede tener 0, 1, 2 o 3 preferences simultaneas: cada type es una fila
-- independiente.
--
-- UNIQUE(user_id, type): evita duplicados del mismo type para el mismo
-- usuario (la app normaliza a Set antes de insertar, pero la DB es la
-- ultima defensa, mismo criterio que el resto del dominio Companion).
-- Sin indice adicional: UNIQUE(user_id, type) ya sirve como indice para
-- lookup por user_id solo (user_id es la columna lider del compuesto) --
-- ninguna query real filtra por type sin pasar por user_id.
--
-- Sin CHECK constraint sobre `type` -- mismo criterio ya establecido desde
-- V1 para columnas VARCHAR respaldadas por enum.
CREATE TABLE companion_preferences (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_companion_preferences_user_id_type UNIQUE (user_id, type)
);
