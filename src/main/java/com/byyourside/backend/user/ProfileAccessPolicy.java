package com.byyourside.backend.user;

import org.springframework.stereotype.Service;

import java.util.UUID;

// Punto unico de decision para "puede este viewer ver el perfil completo de
// este usuario" -- reutilizado por UserService (perfil propio/ajeno,
// discover) y por PostAccessPolicy (un perfil PRIVATE domina sobre la
// visibilidad de sus posts individuales, ver esa clase).
@Service
public class ProfileAccessPolicy {

    public boolean canViewFullProfile(UUID viewerId, User target) {
        return viewerId.equals(target.getId()) || target.getProfileVisibility() == ProfileVisibility.PUBLIC;
    }
}
