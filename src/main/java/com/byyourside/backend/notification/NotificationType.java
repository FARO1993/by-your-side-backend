package com.byyourside.backend.notification;

// Backend Debt B1: NEW_SUPPORT fue renombrado a NEW_POST_RESPONSE -- ahora
// representa CUALQUIER PostResponseType (no solo el soporte binario
// anterior), una sola semantica clara en vez de mantener dos nombres para
// la misma accion. Cambio de contrato: cualquier consumidor que compare
// contra el string literal "NEW_SUPPORT" debe actualizarse (ver
// docs/API_CONTRACT.md y docs/FRONTEND_HANDOFF.md). V12 migra las filas
// `notifications.type = 'NEW_SUPPORT'` historicas a 'NEW_POST_RESPONSE'.
public enum NotificationType {
    NEW_FOLLOWER,
    NEW_COMMENT,
    NEW_POST_RESPONSE,
    NEW_STATUS_REACTION,
    FOLLOW_REQUEST_RECEIVED,
    FOLLOW_REQUEST_ACCEPTED
}