package com.byyourside.backend.user.dto;

/** Elegir avatar: un id del catalogo, o null para volver a las iniciales. */
public record SetAvatarRequest(String avatarId) {
}
