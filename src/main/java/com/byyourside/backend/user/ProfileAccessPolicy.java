package com.byyourside.backend.user;

import com.byyourside.backend.follow.FollowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Punto unico de decision para "puede este viewer ver el perfil completo de
// este usuario" -- reutilizado por UserService (perfil propio/ajeno,
// discover) y por PostAccessPolicy (un perfil PRIVATE domina sobre la
// visibilidad de sus posts individuales, ver esa clase).
//
// Fase 9.3: un perfil PRIVATE ya no bloquea SIEMPRE a terceros -- un
// follower ya ACEPTADO (fila real en `follows`) tambien ve el perfil
// completo. Esto es exactamente equivalente a "existe una fila en
// `follows`", sin necesidad de consultar FollowRequest para nada: una
// solicitud PENDING/REJECTED/CANCELLED nunca crea esa fila (ver
// FollowService/FollowRequestService), asi que este chequeo ya excluye
// automaticamente a cualquiera que no sea un follower efectivo.
@Service
@RequiredArgsConstructor
public class ProfileAccessPolicy {

    private final FollowRepository followRepository;

    public boolean canViewFullProfile(UUID viewerId, User target) {
        if (viewerId.equals(target.getId())) {
            return true;
        }
        if (target.getProfileVisibility() == ProfileVisibility.PUBLIC) {
            return true;
        }
        return followRepository.existsByFollowerIdAndFollowingId(viewerId, target.getId());
    }
}
