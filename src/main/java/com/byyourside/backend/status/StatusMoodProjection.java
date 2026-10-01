package com.byyourside.backend.status;

import java.util.UUID;

// Backend Debt B5.4A: proyeccion minima (userId + mood) para el status
// summary de Discover -- nunca el Status completo (ni id, createdAt,
// expiresAt, reacciones).
public interface StatusMoodProjection {

    UUID getUserId();

    String getMood();
}
