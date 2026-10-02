package com.byyourside.backend.game.dto;

import com.byyourside.backend.game.GameType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateGameRoomRequest(
        @NotNull GameType game,
        @NotNull UUID guestId
) {
}
