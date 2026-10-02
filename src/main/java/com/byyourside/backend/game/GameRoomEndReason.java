package com.byyourside.backend.game;

// UNAVAILABLE cubre un bloqueo entre las dos personas: a proposito no se
// distingue de otras causas para no revelar la relacion de bloqueo.
public enum GameRoomEndReason {
    DECLINED,
    CANCELLED,
    LEFT,
    EXPIRED,
    UNAVAILABLE
}
