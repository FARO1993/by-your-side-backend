package com.byyourside.backend.block;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Punto central unico para chequear si dos usuarios tienen una relacion de
// bloqueo activa, sin importar quien bloqueo a quien. Todo el resto del
// codigo (ProfileAccessPolicy, FollowService, ChatService, NotificationService,
// StatusService, etc.) debe llamar aca en vez de reimplementar el OR
// bilateral -- evita duplicar la logica en cada punto de integracion, mismo
// criterio que ProfileAccessPolicy/PostAccessPolicy en Fase 9.1-9.3.
@Service
@RequiredArgsConstructor
public class BlockPolicy {

    private final UserBlockRepository userBlockRepository;

    public boolean isBlockedBetween(UUID userAId, UUID userBId) {
        if (userAId.equals(userBId)) {
            return false;
        }
        return userBlockRepository.existsBilateral(userAId, userBId);
    }
}
