package com.byyourside.backend.user;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.companion.CompanionOfferingService;
import com.byyourside.backend.companion.CompanionPreferenceService;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
import com.byyourside.backend.follow.FollowState;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.status.StatusMood;
import com.byyourside.backend.status.StatusService;
import com.byyourside.backend.storage.ImageStorageService;
import com.byyourside.backend.user.dto.DiscoverUserResponse;
import com.byyourside.backend.user.dto.PublicUserProfileResponse;
import com.byyourside.backend.user.dto.UpdateProfileRequest;
import com.byyourside.backend.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final CompanionOfferingService companionOfferingService;
    private final StatusService statusService;

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

    static final int DISCOVER_MAX_PAGE_SIZE = 50;
    static final int DISCOVER_MAX_QUERY_LENGTH = 50;

    // Backend Debt B5.1: Discover server-side.
    //
    // - `q` ausente/vacio/en blanco => BROWSE: excluye a quienes ya sigo
    //   (mismo contrato de siempre, "gente nueva").
    // - `q` presente => SEARCH por username/displayName (contains, sin
    //   distinguir mayusculas; nunca email ni bio): los followed SI
    //   aparecen, con followState=FOLLOWING (buscar a alguien que ya seguis
    //   tiene que encontrarlo).
    // En ambos modos: solo cuentas ACTIVE, sin el propio usuario, sin
    // block bilateral ni mute unilateral -- todo DB-side (NOT EXISTS, ver
    // UserRepository), sin listas NOT IN globales. El role NO filtra.
    //
    // Validacion aca (no con @Validated) para que todo sea un
    // ResponseStatusException 400 con el formato de error existente.
    public Page<DiscoverUserResponse> discoverUsers(UserPrincipal principal, String q, int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > DISCOVER_MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be between 1 and " + DISCOVER_MAX_PAGE_SIZE);
        }
        String normalizedQuery = normalizeDiscoverQuery(q);
        if (normalizedQuery != null && normalizedQuery.length() > DISCOVER_MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q must be at most " + DISCOVER_MAX_QUERY_LENGTH + " characters");
        }

        // Sin Sort: el orden total esta en la query (ver UserRepository).
        Pageable pageable = PageRequest.of(page, size);
        boolean searching = normalizedQuery != null;
        Page<User> result = searching
                ? userRepository.searchDiscoverable(principal.getId(), UserStatus.ACTIVE,
                        toContainsPattern(normalizedQuery), pageable)
                : userRepository.browseDiscoverable(principal.getId(), UserStatus.ACTIVE, pageable);

        List<UUID> idsInPage = result.getContent().stream().map(User::getId).toList();
        if (idsInPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, result.getTotalElements());
        }

        // followState en batch (1 query por estado, nunca una por fila).
        // En browse FOLLOWING es imposible por construccion (la query ya
        // excluye followed), asi que ni se consulta. Precedencia igual que
        // getPublicProfile: FOLLOWING > REQUESTED > NONE -- REQUESTED nunca
        // se colapsa en NONE.
        Set<UUID> followingIds = searching
                ? Set.copyOf(followRepository.findFollowingIdsAmong(principal.getId(), idsInPage))
                : Set.of();
        Set<UUID> pendingIds = Set.copyOf(
                followRequestRepository.findPendingOutgoingTargetIdsAmong(principal.getId(), idsInPage));

        // Backend Debt B5.2: `available` en batch -- UNA query por pagina
        // sobre companion_offerings (source of truth), nunca una por
        // usuario. Los ids ya estan autorizados por la query de Discover
        // (block/mute/status/etc.), asi que aca solo se resuelve true/false.
        Set<UUID> availableIds = companionOfferingService.findAvailableUserIdsAmong(idsInPage);

        // Backend Debt B5.4A: `statusMood` en batch -- UNA query por pagina,
        // y solo para los ids cuyo perfil completo ve el viewer: PUBLIC, o
        // PRIVATE con follow ACEPTADO. Es exactamente el gate de
        // ProfileAccessPolicy.canViewFullProfile (que usa GET
        // /api/users/{id}/status) en version batch: el propio usuario y el
        // block ya estan excluidos por la query de Discover, y un follow
        // aceptado es una fila en `follows` (followingIds; en browse esta
        // vacio porque browse excluye a los followed, asi que ahi ningun
        // PRIVATE expone mood). Un PRIVATE sin acceso ni siquiera entra a la
        // query de status: asi no se filtra si tiene un status activo.
        List<UUID> statusVisibleIds = result.getContent().stream()
                .filter(user -> user.getProfileVisibility() == ProfileVisibility.PUBLIC
                        || followingIds.contains(user.getId()))
                .map(User::getId)
                .toList();
        Map<UUID, StatusMood> moodByUserId = statusService.findCurrentMoodsAmong(statusVisibleIds);

        return result.map(user -> new DiscoverUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getProfileVisibility() == ProfileVisibility.PUBLIC ? user.getBio() : null,
                user.getAvatarUrl(),
                user.getProfileVisibility().name(),
                resolveDiscoverFollowState(user.getId(), followingIds, pendingIds).name(),
                availableIds.contains(user.getId()),
                moodByUserId.get(user.getId())
        ));
    }

    private static FollowState resolveDiscoverFollowState(UUID targetId, Set<UUID> followingIds, Set<UUID> pendingIds) {
        if (followingIds.contains(targetId)) {
            return FollowState.FOLLOWING;
        }
        if (pendingIds.contains(targetId)) {
            return FollowState.REQUESTED;
        }
        return FollowState.NONE;
    }

    // trim + colapso de espacios; null si queda vacio (=> browse). La
    // minuscula se aplica en toContainsPattern.
    static String normalizeDiscoverQuery(String q) {
        if (q == null) {
            return null;
        }
        String normalized = q.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    // Escapa \ primero (si no, escaparia las barras que agrega el propio
    // escape de % y _), despues % y _ -- asi ninguno actua como wildcard.
    static String toContainsPattern(String normalizedQuery) {
        String escaped = normalizedQuery.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
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