package com.byyourside.backend.game.dto;

// Lo que llega por WebSocket a /user/queue/game-rooms:
// - INVITATION: te invitaron (room)
// - ROOM: cambio el estado de una sala tuya (room)
// - EVENT: una jugada nueva, propia o de la otra persona (event)
public record GameRoomMessage(
        String kind,
        GameRoomResponse room,
        GameEventResponse event
) {
    public static GameRoomMessage ofInvitation(GameRoomResponse room) {
        return new GameRoomMessage("INVITATION", room, null);
    }

    public static GameRoomMessage ofRoom(GameRoomResponse room) {
        return new GameRoomMessage("ROOM", room, null);
    }

    public static GameRoomMessage ofEvent(GameEventResponse event) {
        return new GameRoomMessage("EVENT", null, event);
    }
}
