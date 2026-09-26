package com.byyourside.backend.follow;

import com.byyourside.backend.follow.dto.FollowResponse;
import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.ProfileVisibility;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final FollowRepository followRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // Fase 9.3: el resultado depende del profileVisibility del target.
    // PUBLIC -> Follow inmediato (comportamiento historico, sin cambios).
    // PRIVATE -> FollowRequest PENDING, sin crear Follow todavia -- el
    // dueno del perfil debe aceptarla (ver FollowRequestService).
    @Transactional
    public FollowResponse follow(UserPrincipal principal, UUID targetUserId) {
        if (principal.getId().equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot follow yourself");
        }

        User follower = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found"));

        // Ya soy follower efectivo: idempotente/coherente sin importar el
        // profileVisibility actual del target (pudo haberse hecho PUBLIC->
        // PRIVATE despues de que ya lo seguia).
        if (followRepository.existsByFollowerIdAndFollowingId(follower.getId(), target.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already following this user");
        }

        if (target.getProfileVisibility() == ProfileVisibility.PRIVATE) {
            return requestFollow(follower, target);
        }

        Follow follow = followRepository.save(Follow.builder()
                .follower(follower)
                .following(target)
                .build());

        notificationService.notify(target, follower, NotificationType.NEW_FOLLOWER, null);

        return new FollowResponse(follower.getId(), target.getId(), follow.getCreatedAt(), FollowState.FOLLOWING.name(), null);
    }

    // Sin duplicar PENDING: si ya existe uno, se devuelve el mismo
    // (idempotente) en vez de crear un segundo. El indice unico parcial de
    // la migracion es la ultima linea de defensa ante una carrera de dos
    // requests concurrentes que pasan el chequeo de "ya existe" al mismo
    // tiempo -- se resuelve igual, sin propagar un 500.
    private FollowResponse requestFollow(User follower, User target) {
        Optional<FollowRequest> existingPending = followRequestRepository.findByRequesterIdAndTargetIdAndStatus(
                follower.getId(), target.getId(), FollowRequestStatus.PENDING);

        if (existingPending.isPresent()) {
            FollowRequest request = existingPending.get();
            return new FollowResponse(follower.getId(), target.getId(), request.getCreatedAt(), FollowState.REQUESTED.name(), request.getId());
        }

        try {
            FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                    .requester(follower)
                    .target(target)
                    .build());

            notificationService.notify(target, follower, NotificationType.FOLLOW_REQUEST_RECEIVED, null);

            return new FollowResponse(follower.getId(), target.getId(), request.getCreatedAt(), FollowState.REQUESTED.name(), request.getId());
        } catch (DataIntegrityViolationException e) {
            FollowRequest raced = followRequestRepository
                    .findByRequesterIdAndTargetIdAndStatus(follower.getId(), target.getId(), FollowRequestStatus.PENDING)
                    .orElseThrow(() -> e);
            return new FollowResponse(follower.getId(), target.getId(), raced.getCreatedAt(), FollowState.REQUESTED.name(), raced.getId());
        }
    }

    @Transactional
    public void unfollow(UserPrincipal principal, UUID targetUserId) {
        Follow follow = followRepository.findByFollowerIdAndFollowingId(principal.getId(), targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You are not following this user"));

        followRepository.delete(follow);
    }

    // Fase 9.3: el usuario actual expulsa a alguien de SUS PROPIOS followers
    // -- direccion inversa a unfollow (ahi el principal es el follower; acá
    // el principal es el following/target). No afecta la relacion opuesta
    // (si yo tambien sigo a esa persona, eso sigue intacto). Corta acceso
    // FOLLOWERS_ONLY/perfil-privado de inmediato: es la misma fila `follows`
    // que consultan ProfileAccessPolicy/PostAccessPolicy.
    @Transactional
    public void removeFollower(UserPrincipal principal, UUID followerUserId) {
        Follow follow = followRepository.findByFollowerIdAndFollowingId(followerUserId, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That user is not following you"));

        followRepository.delete(follow);
    }

    public List<UserSummary> getFollowers(UUID userId) {
        ensureUserExists(userId);

        return followRepository.findByFollowingId(userId).stream()
                .map(f -> toSummary(f.getFollower()))
                .toList();
    }

    public List<UserSummary> getFollowing(UUID userId) {
        ensureUserExists(userId);

        return followRepository.findByFollowerId(userId).stream()
                .map(f -> toSummary(f.getFollowing()))
                .toList();
    }


    private void ensureUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl());
    }
}
