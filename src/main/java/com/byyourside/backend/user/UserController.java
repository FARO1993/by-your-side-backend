package com.byyourside.backend.user;

import com.byyourside.backend.block.BlockService;
import com.byyourside.backend.block.dto.BlockedUserResponse;
import com.byyourside.backend.companion.CompanionOfferingService;
import com.byyourside.backend.companion.CompanionPreferenceService;
import com.byyourside.backend.companion.dto.CompanionAvailabilityResponse;
import com.byyourside.backend.companion.dto.CompanionPreferencesResponse;
import com.byyourside.backend.companion.dto.SetCompanionPreferencesRequest;
import com.byyourside.backend.mute.MuteService;
import com.byyourside.backend.mute.dto.MutedUserResponse;
import com.byyourside.backend.post.PostService;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.status.StatusService;
import com.byyourside.backend.status.dto.StatusResponse;
import com.byyourside.backend.user.dto.DiscoverUserResponse;
import com.byyourside.backend.user.dto.PublicUserProfileResponse;
import com.byyourside.backend.user.dto.SetAvatarRequest;
import com.byyourside.backend.user.dto.UpdateProfileRequest;
import com.byyourside.backend.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PostService postService;
    private final BlockService blockService;
    private final MuteService muteService;
    private final StatusService statusService;
    private final CompanionOfferingService companionOfferingService;
    private final CompanionPreferenceService companionPreferenceService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                      @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(principal, request));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                                      @PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getPublicProfile(principal, userId));
    }

    @GetMapping("/{userId}/posts")
    public ResponseEntity<Page<PostResponse>> getUserPosts(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable UUID userId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(postService.getUserPosts(principal, userId, pageable));
    }

    // Backend Debt B2: contrato directo para el status/mood actual de
    // userId -- nunca infiere desde /api/statuses/feed. 200 con body null
    // si es accesible pero no tiene status activo (mismo criterio que
    // GET /api/availability/mine); 404 genérico si userId no existe o si
    // el viewer no puede ver su perfil completo (privado sin follower
    // aceptado, o bloqueo en cualquier dirección) -- nunca revela cuál de
    // los dos motivos aplicó.
    @GetMapping("/{userId}/status")
    public ResponseEntity<StatusResponse> getCurrentStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable UUID userId) {
        return ResponseEntity.ok(statusService.getCurrentStatus(principal, userId));
    }

    // Backend Debt B4B.4: disponibilidad publica minima -- a diferencia de
    // /status (arriba), deliberadamente NO usa ProfileAccessPolicy: perfil
    // PRIVATE sin accepted follower no bloquea esta ruta, el Offering
    // activo es un consentimiento especifico de Companion (decision B4A
    // #3). Unica regla de corte fuerte: bloqueo bilateral (404 generico,
    // mismo criterio que el resto de la API). 200 con body null si no hay
    // Offering activa. Ver CompanionOfferingService.getPublicAvailability.
    @GetMapping("/{userId}/availability")
    public ResponseEntity<CompanionAvailabilityResponse> getPublicAvailability(@AuthenticationPrincipal UserPrincipal principal,
                                                                                @PathVariable UUID userId) {
        return ResponseEntity.ok(companionOfferingService.getPublicAvailability(principal, userId));
    }

    @GetMapping("/discover")
    public ResponseEntity<Page<DiscoverUserResponse>> discoverUsers(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @RequestParam(required = false) String q,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        // Backend Debt B5.1: la validacion de page/size/q vive en el
        // service (400), no aca -- ver UserService.discoverUsers.
        return ResponseEntity.ok(userService.discoverUsers(principal, q, page, size));
    }

    // Avatar ilustrado (sin fotos). Body: { "avatarId": "hoja" } o { "avatarId": null }.
    @PutMapping("/me/avatar")
    public ResponseEntity<UserResponse> setAvatar(@AuthenticationPrincipal UserPrincipal principal,
                                                  @RequestBody SetAvatarRequest request) {
        return ResponseEntity.ok(userService.setAvatar(principal, request.avatarId()));
    }

    // El blocker es SIEMPRE el usuario autenticado -- nunca se acepta un
    // blockerId por body/path distinto al principal.
    @PostMapping("/{userId}/block")
    public ResponseEntity<Void> blockUser(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable UUID userId) {
        blockService.blockUser(principal.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/block")
    public ResponseEntity<Void> unblockUser(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable UUID userId) {
        blockService.unblockUser(principal.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/blocked")
    public ResponseEntity<Page<BlockedUserResponse>> getBlockedUsers(@AuthenticationPrincipal UserPrincipal principal,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(blockService.getBlockedUsers(principal.getId(), pageable));
    }

    // El muter es SIEMPRE el usuario autenticado -- nunca se acepta un
    // muterId por body/path distinto al principal (Fase 9.5).
    @PostMapping("/{userId}/mute")
    public ResponseEntity<Void> muteUser(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable UUID userId) {
        muteService.muteUser(principal.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/mute")
    public ResponseEntity<Void> unmuteUser(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable UUID userId) {
        muteService.unmuteUser(principal.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/muted")
    public ResponseEntity<Page<MutedUserResponse>> getMutedUsers(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(muteService.getMutedUsers(principal.getId(), pageable));
    }

    // Backend Debt B4B.5: "como suelo estar para otros" -- dato ESTABLE de
    // perfil, sin relacion con Need/Offering (nunca se infiere de ellos, ni
    // los sincroniza). Siempre 200: lista vacia (nunca null) cuando el
    // usuario no configuro ninguna preference -- a diferencia de como
    // viaja este mismo dato embebido en PublicUserProfileResponse (ahi SI
    // puede ser null, ver ese DTO), porque aca no hay ninguna regla de
    // privacidad que "ocultar" -- es siempre el propio usuario consultando
    // lo suyo.
    @GetMapping("/me/companion-preferences")
    public ResponseEntity<CompanionPreferencesResponse> getMyCompanionPreferences(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(companionPreferenceService.getMine(principal));
    }

    // PATCH reemplaza el set COMPLETO -- nunca add/remove incremental.
    // `types: []` es valido y borra todas las preferences existentes.
    @PatchMapping("/me/companion-preferences")
    public ResponseEntity<CompanionPreferencesResponse> updateMyCompanionPreferences(@AuthenticationPrincipal UserPrincipal principal,
                                                                                      @Valid @RequestBody SetCompanionPreferencesRequest request) {
        return ResponseEntity.ok(companionPreferenceService.replacePreferences(principal, request.types()));
    }
}