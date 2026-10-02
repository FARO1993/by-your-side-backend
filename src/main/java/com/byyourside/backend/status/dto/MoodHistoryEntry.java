package com.byyourside.backend.status.dto;

import java.time.Instant;
import java.util.UUID;

// Una entrada del historial de animo propio (GET /api/statuses/mine/history).
// A proposito sin `user`, sin `expiresAt` y sin reacciones: es una vista
// PRIVATA de "como estuve estos dias", no un status social.
public record MoodHistoryEntry(
        UUID id,
        String mood,
        Instant createdAt
) {
}
