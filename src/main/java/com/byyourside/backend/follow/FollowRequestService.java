package com.byyourside.backend.follow;

import com.byyourside.backend.follow.dto.FollowRequestResponse;
import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Aceptar/rechazar/cancelar una FollowRequest -- FollowService.follow() ya
// es responsable de CREARLA (idempotente) cuando el target es PRIVATE, este
// service resuelve el tramite despues de creado.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowRequestService {

    private final FollowRequestRepository followRequestRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;

    @Transactional
    public FollowRequestResponse accept(UserPrincipal principal, UUID requestId) {
        FollowRequest request = findOwnedByTarget(principal, requestId, "accept");

        int claimed = followRequestRepository.claimAccept(requestId);
        if (claimed == 0) {
            // Alguien mas (otra request concurrente, o el propio requester
            // cancelando) ya resolvio esta solicitud entre la lectura de
            // arriba y este claim atomico.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow request is no longer pending");
        }

        User requester = request.getRequester();
        User target = request.getTarget();

        // Guarda extra: si por algun motivo ya existiera un Follow real para
        // este par (no deberia, dado el chequeo de FollowService.follow()),
        // no intentamos duplicarlo.
        if (!followRepository.existsByFollowerIdAndFollowingId(requester.getId(), target.getId())) {
            followRepository.save(Follow.builder().follower(requester).following(target).build());
        }

        // Backend Debt B3: followRequestId para contexto (no imprescindible
        // -- ya no hay una accion pendiente sobre este tramite, ver
        // BACKEND_ARCHITECTURE.md), sin costo extra ya que `request` ya
        // esta cargado en este punto.
        notificationService.notify(requester, target, NotificationType.FOLLOW_REQUEST_ACCEPTED,
                null, null, request.getId());

        request.setStatus(FollowRequestStatus.ACCEPTED);
        request.setRespondedAt(Instant.now());
        return toResponse(request, requester);
    }

    @Transactional
    public FollowRequestResponse reject(UserPrincipal principal, UUID requestId) {
        FollowRequest request = findOwnedByTarget(principal, requestId, "reject");

        int claimed = followRequestRepository.claimReject(requestId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow request is no longer pending");
        }

        // Deliberadamente sin notificacion -- rechazar no aporta valor
        // suficiente como para justificar avisarle al requester (ver
        // docs/BACKEND_ARCHITECTURE.md § Follow requests).
        request.setStatus(FollowRequestStatus.REJECTED);
        request.setRespondedAt(Instant.now());
        return toResponse(request, request.getRequester());
    }

    @Transactional
    public void cancel(UserPrincipal principal, UUID requestId) {
        FollowRequest request = followRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Follow request not found"));

        if (!request.getRequester().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own follow requests");
        }
        if (request.getStatus() != FollowRequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow request is no longer pending");
        }

        int claimed = followRequestRepository.claimCancel(requestId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow request is no longer pending");
        }
    }

    public List<FollowRequestResponse> getIncoming(UUID targetId) {
        return followRequestRepository.findPendingIncoming(targetId).stream()
                .map(r -> toResponse(r, r.getRequester()))
                .toList();
    }

    public List<FollowRequestResponse> getOutgoing(UUID requesterId) {
        return followRequestRepository.findPendingOutgoing(requesterId).stream()
                .map(r -> toResponse(r, r.getTarget()))
                .toList();
    }

    // Comun a accept/reject: 404 si no existe, 403 si no sos el target
    // (mismo criterio que updatePost/deletePost -- ownership de un recurso
    // propio, no un tema de visibilidad de contenido que deba ocultarse via
    // 404 generico), 409 si ya no esta PENDING.
    private FollowRequest findOwnedByTarget(UserPrincipal principal, UUID requestId, String action) {
        FollowRequest request = followRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Follow request not found"));

        if (!request.getTarget().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only " + action + " requests sent to you");
        }
        if (request.getStatus() != FollowRequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow request is no longer pending");
        }

        return request;
    }

    private FollowRequestResponse toResponse(FollowRequest request, User otherUser) {
        UserSummary summary = new UserSummary(
                otherUser.getId(), otherUser.getUsername(), otherUser.getDisplayName(), otherUser.getAvatarId());

        return new FollowRequestResponse(request.getId(), summary, request.getCreatedAt(), request.getStatus().name());
    }
}
