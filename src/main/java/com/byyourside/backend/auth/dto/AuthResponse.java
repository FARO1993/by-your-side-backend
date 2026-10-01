package com.byyourside.backend.auth.dto;

// Breaking change deliberado en Fase 1.5: "token" pasa a "accessToken" +
// "refreshToken" en vez de convivir ambos nombres -- ver FRONTEND_HANDOFF.md.
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String username,
        String role
) {
}