package com.byyourside.backend.game.dto;

import com.byyourside.backend.game.GameRoomEndReason;
import com.byyourside.backend.game.GameRoomStatus;
import com.byyourside.backend.game.GameType;
import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

// `expiresAt` solo tiene valor mientras la sala es una invitacion pendiente.
public record GameRoomResponse(
        UUID id,
        GameType game,
        GameRoomStatus status,
        GameRoomEndReason endReason,
        UserSummary host,
        UserSummary guest,
        long seed,
        int eventCount,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Instant expiresAt
) {
}
