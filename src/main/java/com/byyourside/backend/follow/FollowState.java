package com.byyourside.backend.follow;

// Estado resumido de la relacion entre el viewer y otro usuario -- distinto
// de FollowRequestStatus (que es el historial de UN tramite puntual).
// FollowState responde "como estoy parado HOY frente a esta persona":
// NONE = ni sigo ni tengo una solicitud pendiente, REQUESTED = mande una
// solicitud que sigue pendiente (perfil PRIVATE del target), FOLLOWING =
// hay una relacion Follow real/aceptada.
public enum FollowState {
    NONE,
    REQUESTED,
    FOLLOWING
}
