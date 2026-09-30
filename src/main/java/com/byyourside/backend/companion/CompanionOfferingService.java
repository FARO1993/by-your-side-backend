package com.byyourside.backend.companion;

import com.byyourside.backend.block.BlockPolicy;
import com.byyourside.backend.companion.dto.CompanionAvailabilityResponse;
import com.byyourside.backend.companion.dto.CompanionCandidateResponse;
import com.byyourside.backend.companion.dto.CompanionOfferingResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanionOfferingService {

    // Mismo criterio que CompanionNeedService (B4B.1): dos PUT concurrentes
    // alcanzan con un solo reintento, el margen extra es solo defensivo.
    private static final int MAX_ATTEMPTS = 3;

    // MVP a proposito (Backend Debt B4B.2): sin paginacion, sin scoring --
    // mismo limite fijo que la extinta AvailabilityService legacy.
    private static final int DEFAULT_LIMIT = 10;

    private final CompanionOfferingRepository companionOfferingRepository;
    private final CompanionOfferingWriter companionOfferingWriter;
    private final CompanionNeedRepository companionNeedRepository;
    private final UserRepository userRepository;
    private final BlockPolicy blockPolicy;

    // Solo un Offering activo por vez: reemplaza cualquier declaracion
    // anterior -- mismo criterio que CompanionNeedService, exclusividad
    // garantizada a nivel DB (UNIQUE(user_id), ver V15).
    public CompanionOfferingResponse setOffering(UserPrincipal principal, OfferingType type) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        CompanionOffering offering = replaceWithRetry(user, type, MAX_ATTEMPTS);
        return toResponse(offering);
    }

    // La DB es la ultima defensa contra dos PUT concurrentes -- mismo
    // patron exacto que CompanionNeedService.replaceWithRetry (ver ahi el
    // detalle del porque CompanionOfferingWriter.replace corre en su propia
    // transaccion REQUIRES_NEW).
    private CompanionOffering replaceWithRetry(User user, OfferingType type, int attemptsLeft) {
        try {
            return companionOfferingWriter.replace(user, type);
        } catch (DataIntegrityViolationException e) {
            if (attemptsLeft <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Could not save your companion offering, please retry");
            }
            return replaceWithRetry(user, type, attemptsLeft - 1);
        }
    }

    @Transactional
    public void cancelOffering(UserPrincipal principal) {
        companionOfferingRepository.deleteByUserId(principal.getId());
    }

    // Ausencia genuina de dato = 200 con body null, nunca 404 -- mismo
    // criterio que CompanionNeedService.getMine / la extinta AvailabilityService.getMine.
    public CompanionOfferingResponse getMine(UserPrincipal principal) {
        return companionOfferingRepository
                .findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(principal.getId(), Instant.now())
                .map(this::toResponse)
                .orElse(null);
    }

    // Busqueda directa por tipo exacto. Decision B4A #3: sin filtro de
    // ProfileVisibility/Follow -- el Offering activo es el consentimiento
    // especifico para aparecer aca, ver
    // CompanionOfferingRepository.findRandomCandidatesByType.
    public List<CompanionCandidateResponse> searchByType(UserPrincipal principal, OfferingType type) {
        return searchCandidatesRaw(principal, type).stream()
                .map(this::toCandidateResponse)
                .toList();
    }

    // Backend Debt B4B.3: proyeccion sin transformar -- unicamente para el
    // adapter legacy (AvailabilityController), que necesita el id/createdAt
    // reales de la Offering para mantener el shape historico de
    // AvailabilityResponse. searchByType (arriba, el endpoint nuevo) sigue
    // sin exponer esos campos en CompanionCandidateResponse -- esto no
    // cambia el contrato del dominio nuevo (B4B.2).
    public List<CompanionCandidateProjection> searchCandidatesRaw(UserPrincipal principal, OfferingType type) {
        return companionOfferingRepository
                .findRandomCandidatesByType(type.name(), Instant.now(), principal.getId(), DEFAULT_LIMIT);
    }

    // Backend Debt B4B.3: usado por ChatService para decidir si el target
    // esta disponible para companionship -- reemplaza a la extinta
    // AvailabilityRepository.existsByUserIdAndExpiresAtAfter. Cualquier
    // OfferingType activo sirve para desbloquear el primer contacto --
    // ChatService no hace matching, solo autoriza (el Need del caller nunca
    // participa de esta decision).
    public boolean hasActiveOffering(UUID userId) {
        return companionOfferingRepository.existsByUserIdAndExpiresAtAfter(userId, Instant.now());
    }

    // Backend Debt B5.2: version batch de hasActiveOffering, usada por
    // Discover (UserService.discoverUsers) para `available`. Una sola query
    // por pagina. No aplica block/mute/profileVisibility: los ids ya vienen
    // autorizados por la query de Discover. Lista vacia => ni se consulta
    // (evita un IN () vacio y una query innecesaria).
    public Set<UUID> findAvailableUserIdsAmong(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return companionOfferingRepository.findAvailableUserIdsAmong(userIds, Instant.now());
    }

    // Backend Debt B4B.2: "Need -> candidatos compatibles" (decision B4A
    // #4, flujo MVP -- Need -> candidatos compatibles -> eleccion del
    // usuario -> POST conversation existente). Sin Need activo no hay
    // ningun criterio con el que buscar: 200 con lista vacia, nunca 404 --
    // no es un recurso inexistente, es la ausencia de un criterio de
    // busqueda (mismo espiritu que /mine devolviendo null en vez de 404).
    // El Need del usuario NUNCA se expone ni viaja en el Candidate DTO --
    // solo se usa internamente para resolver el OfferingType compatible
    // via CompanionCompatibility (Need sigue siendo privado, decision B4A
    // #10).
    public List<CompanionCandidateResponse> searchCompatibleWithMyNeed(UserPrincipal principal) {
        return companionNeedRepository
                .findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(principal.getId(), Instant.now())
                .map(need -> CompanionCompatibility.compatibleOfferingFor(need.getType()))
                .map(compatibleType -> searchByType(principal, compatibleType))
                .orElse(List.of());
    }

    // Backend Debt B4B.4: disponibilidad publica minima de un usuario --
    // "esta disponible ahora, y de que forma" (decision B4A #3, reafirmada
    // para este endpoint). Deliberadamente NO usa ProfileAccessPolicy ni
    // consulta FollowRepository -- perfil PRIVATE, FollowState REQUESTED o
    // NONE no importan aca: el Offering activo YA ES el consentimiento
    // especifico de Companion, no equivale a un accepted follower y nunca
    // desbloquea perfil/bio/posts/status. La UNICA regla que corta el
    // acceso es el bloqueo bilateral -- mismo criterio que el resto de la
    // API, 404 generico que nunca revela si el motivo fue "no existe" o
    // "hay un bloqueo" (tampoco cual de los dos bloqueo a cual). Mute es
    // unilateral y NUNCA es control de acceso aca -- si el viewer muteo al
    // target, igual puede consultar esta ruta directa con normalidad; el
    // mute solo excluye de superficies agregadas (searchByType/
    // searchCompatibleWithMyNeed), nunca de un lookup directo por userId.
    // Ausencia de Offering activa (o expirada) = null, nunca 404 -- misma
    // convencion que getMine.
    public CompanionAvailabilityResponse getPublicAvailability(UserPrincipal principal, UUID targetUserId) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (blockPolicy.isBlockedBetween(principal.getId(), target.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        return companionOfferingRepository
                .findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(targetUserId, Instant.now())
                .map(offering -> new CompanionAvailabilityResponse(true, offering.getType().name(), offering.getExpiresAt()))
                .orElse(null);
    }

    private CompanionOfferingResponse toResponse(CompanionOffering offering) {
        return new CompanionOfferingResponse(
                offering.getId(),
                offering.getType().name(),
                offering.getCreatedAt(),
                offering.getExpiresAt()
        );
    }

    private CompanionCandidateResponse toCandidateResponse(CompanionCandidateProjection projection) {
        UserSummary user = new UserSummary(
                projection.getUserId(),
                projection.getUsername(),
                projection.getDisplayName(),
                projection.getAvatarUrl()
        );
        return new CompanionCandidateResponse(user, projection.getOfferingType(), projection.getExpiresAt());
    }
}
