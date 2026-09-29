package com.byyourside.backend.user;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.companion.CompanionPreferenceService;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
import com.byyourside.backend.follow.FollowState;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.storage.ImageStorageService;
import com.byyourside.backend.user.dto.DiscoverUserResponse;
import com.byyourside.backend.user.dto.PublicUserProfileResponse;
import com.byyourside.backend.user.dto.UpdateProfileRequest;
import com.byyourside.backend.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final FollowRequestRepository followRequestRepository;
    private final ImageStorageService imageStorageService;
    private final ProfileAccessPolicy profileAccessPolicy;
    private final UserBlockRepository userBlockRepository;
    private final UserMuteRepository userMuteRepository;
    private final CompanionPreferenceService companionPreferenceService;

    public UserResponse getCurrentUser(UserPrincipal principal) {
        User user = findByIdOrThrow(principal.getId());
        return toResponse(user);
    }

    // Backend Debt B2: null = no tocar (sin cambios, ya era el criterio de
    // esta fase para los 4 campos). Lo nuevo es la normalizacion de texto:
    // - displayName: trim + rechazo si queda vacio (400) -- displayName
    //   puede ser null a nivel de todo el sistema (nunca se exigio en
    //   registro, ver RegisterRequest/AuthService), pero un PATCH explicito
    //   que lo deja en blanco (solo espacios) es casi seguro un error del
    //   cliente, no una intencion real de "vaciar" -- si se quiere no tener
    //   displayName, la via es no mandar el campo (omitirlo), no mandar "".
    // - bio: trim + blank -> null (limpiar bio explicitamente SI es una
    //   operacion valida e intencional -- "" y null significan lo mismo
    //   conceptualmente para bio en todo el resto del codigo, ver
    //   PublicUserProfileResponse: `fullProfile ? target.getBio() : null`).
    // Texto plano en ambos casos -- sin sanitizacion HTML: la UI escapa al
    // renderizar (unico lugar que ya necesitaba escape, el saludo de los
    // emails transaccionales, tiene su propio escape() dedicado, ver
    // ResendEmailService).
    public UserResponse updateProfile(UserPrincipal principal, UpdateProfileRequest request) {
        User user = findByIdOrThrow(principal.getId());

        if (request.displayName() != null) {
            String trimmed = request.displayName().trim();
            if (trimmed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName cannot be blank");
            }
            user.setDisplayName(trimmed);
        }
        if (request.bio() != null) {
            String trimmed = request.bio().trim();
            user.setBio(trimmed.isEmpty() ? null : trimmed);
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.profileVisibility() != null) {
            user.setProfileVisibility(request.profileVisibility());
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateRole(UUID targetUserId, UserRole newRole) {
        User target = findByIdOrThrow(targetUserId);

        // Evita que la cola de moderacion se quede sin nadie que pueda resolverla.
        if (target.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN) {
            if (userRepository.countByRole(UserRole.ADMIN) <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last remaining admin");
            }
        }

        target.setRole(newRole);
        target = userRepository.save(target);
        return toResponse(target);
    }

    public PublicUserProfileResponse getPublicProfile(UserPrincipal principal, UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Fase 9.4: si el TARGET me bloqueo a mi, su perfil se trata como
        // inexistente -- mismo 404 generico que un usuario que nunca existio,
        // a proposito indistinguible (no revela que fui bloqueado). Si en
        // cambio soy YO quien lo bloqueo, sigo pudiendo ver esta tarjeta
        // (limitada, ver blockedByCurrentUser mas abajo) para poder
        // desbloquearlo desde su perfil.
        if (!principal.getId().equals(userId)
                && userBlockRepository.existsByBlockerIdAndBlockedId(userId, principal.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        boolean blockedByCurrentUser = !principal.getId().equals(userId)
                && userBlockRepository.existsByBlockerIdAndBlockedId(principal.getId(), userId);

        // Fase 9.5: a diferencia de blockedByCurrentUser, mutear no es
        // control de acceso -- este flag no participa en ningun chequeo de
        // arriba/abajo (ni 404, ni fullProfile), solo se expone para que el
        // frontend pueda mostrar el boton Silenciar/Dejar de silenciar.
        boolean mutedByCurrentUser = !principal.getId().equals(userId)
                && userMuteRepository.existsByMuterIdAndMutedId(principal.getId(), userId);

        long followersCount = followRepository.countByFollowingId(userId);
        long followingCount = followRepository.countByFollowerId(userId);
        boolean isOwnProfile = principal.getId().equals(userId);
        boolean followedByCurrentUser = !isOwnProfile
                && followRepository.existsByFollowerIdAndFollowingId(principal.getId(), userId);

        FollowState followState;
        if (isOwnProfile) {
            followState = FollowState.NONE;
        } else if (followedByCurrentUser) {
            followState = FollowState.FOLLOWING;
        } else if (followRequestRepository.existsByRequesterIdAndTargetIdAndStatus(
                principal.getId(), userId, FollowRequestStatus.PENDING)) {
            followState = FollowState.REQUESTED;
        } else {
            followState = FollowState.NONE;
        }

        // Perfil privado ajeno: nunca 404 (el viewer debe poder saber que la
        // cuenta existe y seguir/dejar de seguir), pero la bio no viaja --
        // vista limitada, ver PublicUserProfileResponse -- salvo que el
        // viewer ya sea un follower aceptado (Fase 9.3).
        boolean fullProfile = profileAccessPolicy.canViewFullProfile(principal.getId(), target);

        // Backend Debt B4B.5: solo se consulta companion_preferences cuando
        // el perfil completo ya es visible -- evita una query cuyo
        // resultado se descartaria para un perfil limitado. `null` (no `[]`)
        // en ese caso, mismo criterio que `bio` arriba -- ver
        // PublicUserProfileResponse para el porque exacto.
        List<String> companionPreferences = fullProfile
                ? companionPreferenceService.findOrderedTypesForFullProfile(target.getId())
                : null;

        return new PublicUserProfileResponse(
                target.getId(),
                target.getUsername(),
                target.getDisplayName(),
                fullProfile ? target.getBio() : null,
                target.getAvatarUrl(),
                target.getCreatedAt(),
                followersCount,
                followingCount,
                followedByCurrentUser,
                target.getProfileVisibility().name(),
                followState.name(),
                blockedByCurrentUser,
                mutedByCurrentUser,
                companionPreferences
        );
    }

    public Page<DiscoverUserResponse> discoverUsers(UserPrincipal principal, Pageable pageable) {
        Set<UUID> excludedIds = followRepository.findByFollowerId(principal.getId()).stream()
                .map(follow -> follow.getFollowing().getId())
                .collect(Collectors.toSet());

        // Fase 9.4: exclusion bilateral de bloqueo -- 2 consultas batch para
        // toda la lista (a quien bloquee + quien me bloqueo a mi), no una
        // consulta por fila de discover.
        excludedIds.addAll(userBlockRepository.findBlockedIdsByBlocker(principal.getId()));
        excludedIds.addAll(userBlockRepository.findBlockerIdsByBlocked(principal.getId()));

        // Fase 9.5: exclusion UNILATERAL de mute -- solo "a quien yo
        // muteo" (a diferencia de block, no hay equivalente a
        // findBlockerIdsByBlocked: que alguien me haya muteado a mi no me
        // saca de SU discover en ningun sentido reciproco, porque yo no soy
        // quien filtra ahi).
        excludedIds.addAll(userMuteRepository.findMutedIdsByMuter(principal.getId()));

        excludedIds.add(principal.getId());

        Page<User> page = userRepository.findByIdNotIn(excludedIds, pageable);

        // Batch, no N+1: una sola consulta para saber que targets de ESTA
        // pagina tienen un FollowRequest PENDING mio, en vez de una consulta
        // por fila (discover ya excluye a quienes sigo, asi que el unico
        // otro estado posible aca es REQUESTED).
        List<UUID> idsInPage = page.getContent().stream().map(User::getId).toList();
        Set<UUID> pendingTargetIds = idsInPage.isEmpty()
                ? Set.of()
                : Set.copyOf(followRequestRepository.findPendingOutgoingTargetIdsAmong(principal.getId(), idsInPage));

        return page.map(user -> new DiscoverUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getProfileVisibility() == ProfileVisibility.PUBLIC ? user.getBio() : null,
                user.getAvatarUrl(),
                user.getProfileVisibility().name(),
                (pendingTargetIds.contains(user.getId()) ? FollowState.REQUESTED : FollowState.NONE).name()
        ));
    }

    @Transactional
    public UserResponse updateAvatar(UserPrincipal principal, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image");
        }

        User user = findByIdOrThrow(principal.getId());

        String avatarUrl;
        try {
            avatarUrl = imageStorageService.uploadUserAvatar(user.getId(), file);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload avatar");
        }

        user.setAvatarUrl(avatarUrl);
        user = userRepository.save(user);
        return toResponse(user);
    }


    private User findByIdOrThrow(java.util.UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.getCreatedAt(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.getProfileVisibility().name()
        );
    }
}