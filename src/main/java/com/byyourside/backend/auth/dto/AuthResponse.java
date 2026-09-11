package com.byyourside.backend.auth.dto;

public record AuthResponse(
        String token,
        String username,
        String role
) {
}