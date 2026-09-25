package com.byyourside.backend.auth.dto;

public record RefreshResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
