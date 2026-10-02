package com.byyourside.backend.game.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record GameEventResponse(
        UUID roomId,
        int seq,
        UUID actorId,
        String type,
        JsonNode payload,
        Instant createdAt
) {
}
