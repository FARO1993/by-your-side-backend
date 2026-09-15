package com.byyourside.backend.post.dto;

import java.util.UUID;

// Datos minimos del autor que se muestran junto a un post.
// A proposito no reusamos UserResponse completo aca (no queremos exponer
// email ni createdAt del autor en cada post del feed).
public record AuthorSummary(
        UUID id,
        String username,
        String displayName,
        String avatarUrl
) {
}