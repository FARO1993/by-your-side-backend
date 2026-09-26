package com.byyourside.backend.user;

import com.byyourside.backend.block.BlockPolicy;
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
//
// Fase 9.4: un bloqueo (en CUALQUIER direccion) corta el acceso al perfil
// completo antes de mirar profileVisibility -- asi que tambien corta,
// gratis, el acceso via PostAccessPolicy.canView (que delegaba aca para
// FOLLOWERS_ONLY/PRIVATE) y hasta para posts PUBLIC de un perfil PUBLIC, que
// de otro modo pasarian este chequeo sin llegar nunca a mirar el bloqueo.
// Este es el UNICO lugar donde se agrega el chequeo -- ni PostAccessPolicy
// ni CommentService/PostSupportService necesitan un cambio propio (mismo
// patron de propagacion sin duplicar logica que Fase 9.3).
//
// Nota: esto NO oculta la existencia del perfil (eso lo decide
// UserService.getPublicProfile con un 404 aparte, solo cuando el TARGET me
// bloqueo a mi) -- aca solo se decide si el contenido completo (bio, posts)
// es visible. Si yo bloquee al target, sigo pudiendo ver su tarjeta de
// perfil limitada (para poder desbloquearlo), pero no su contenido completo
// -- mismo tratamiento que un perfil PRIVATE del que no soy follower.
@Service
@RequiredArgsConstructor
public class ProfileAccessPolicy {

    private final FollowRepository followRepository;
    private final BlockPolicy blockPolicy;

    public boolean canViewFullProfile(UUID viewerId, User target) {
        if (viewerId.equals(target.getId())) {
            return true;
        }
        if (blockPolicy.isBlockedBetween(viewerId, target.getId())) {
            return false;
        }
        if (target.getProfileVisibility() == ProfileVisibility.PUBLIC) {
            return true;
        }
        return followRepository.existsByFollowerIdAndFollowingId(viewerId, target.getId());
    }
}
