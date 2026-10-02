package com.byyourside.backend.game.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// `type` en MAYUSCULAS (FLIP, PLACE, WATER...). `payload` es JSON libre que
// el backend no interpreta, con un tope de tamaño (ver GameRoomService).
public record GameEventRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z_]{0,31}$") String type,
        @NotNull JsonNode payload
) {
}
