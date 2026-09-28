-- Backend Debt B3: agrega referencias navegables explicitas para los tipos
-- de notificacion que hoy no las tenian -- NEW_STATUS_REACTION no llevaba
-- ningun identificador al status (nunca debe reusar post_id, son dominios
-- distintos), y FOLLOW_REQUEST_RECEIVED/FOLLOW_REQUEST_ACCEPTED no llevaban
-- el id del tramite que el frontend necesita para poder ejecutar
-- aceptar/rechazar directamente desde la notificacion.
--
-- Sin FK en ninguna de las dos columnas -- mismo criterio ya establecido por
-- post_id (ver V1): ninguna de las dos son necesarias porque ni Status
-- (solo expira, nunca se borra) ni FollowRequest (ACCEPTED/REJECTED/
-- CANCELLED quedan como historial minimo, nunca se borran) tienen un
-- escenario real de fila eliminada que rompa la referencia. Nullable para
-- no romper filas historicas (quedan en NULL, la deserializacion no se ve
-- afectada).
ALTER TABLE notifications ADD COLUMN status_id UUID;
ALTER TABLE notifications ADD COLUMN follow_request_id UUID;
