package com.byyourside.backend.post;

import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.user.ProfileAccessPolicy;
import com.byyourside.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Punto unico de decision para "puede este viewer ver este post" -- evita
// repartir la logica de visibilidad entre PostService, CommentService y
// PostSupportService (cada uno cargaba el Post directo del repository y
// solo chequeaba que existiera, sin validar nada mas: comentar o apoyar un
// post FOLLOWERS_ONLY/PRIVATE ajeno era posible via esas rutas laterales
// antes de esta fase).
//
// Regla: el perfil PRIVATE del autor domina sobre PostVisibility para
// terceros -- un post PUBLIC de un autor con perfil PRIVATE sigue siendo
// invisible para cualquiera que no sea el propio autor (ver
// docs/BACKEND_ARCHITECTURE.md § Privacidad).
@Service
@RequiredArgsConstructor
public class PostAccessPolicy {

    private final FollowRepository followRepository;
    private final ProfileAccessPolicy profileAccessPolicy;

    public boolean canView(UUID viewerId, Post post) {
        if (post.getStatus() != PostStatus.VISIBLE) {
            return false;
        }

        User author = post.getAuthor();
        if (viewerId.equals(author.getId())) {
            return true;
        }

        if (!profileAccessPolicy.canViewFullProfile(viewerId, author)) {
            return false;
        }

        boolean isFollower = followRepository.existsByFollowerIdAndFollowingId(viewerId, author.getId());
        return switch (post.getVisibility()) {
            case PUBLIC -> true;
            case FOLLOWERS_ONLY -> isFollower;
            case PRIVATE -> false; // el autor ya salio por el chequeo de arriba
        };
    }
}
