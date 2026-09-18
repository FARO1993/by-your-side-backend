package com.byyourside.backend.status;

import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.status.dto.StatusResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatusService {

    private static final long EXPIRATION_HOURS = 24;

    private final StatusRepository statusRepository;
    private final StatusReactionRepository statusReactionRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;

    @Transactional
    public StatusResponse setStatus(UserPrincipal principal, StatusMood mood) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Status status = statusRepository.save(Status.builder()
                .user(user)
                .mood(mood)
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build());

        return toResponse(status, 0, null);
    }

    public List<StatusResponse> getFeed(UserPrincipal principal) {
        List<UUID> relevantUserIds = followRepository.findByFollowerId(principal.getId()).stream()
                .map(follow -> follow.getFollowing().getId())
                .collect(Collectors.toList());
        relevantUserIds.add(principal.getId());

        List<Status> activeStatuses = statusRepository.findActiveStatusesForUsers(relevantUserIds, Instant.now());

        // La query ya viene ordenada por usuario + mas reciente primero:
        // nos quedamos con la primera ocurrencia de cada usuario (su estado actual).
        Map<UUID, Status> currentByUser = new LinkedHashMap<>();
        for (Status status : activeStatuses) {
            currentByUser.putIfAbsent(status.getUser().getId(), status);
        }

        List<Status> currentStatuses = new ArrayList<>(currentByUser.values());
        currentStatuses.sort(Comparator.comparing(Status::getCreatedAt).reversed());

        return enrichAndMap(principal, currentStatuses);
    }

    @Transactional
    public StatusResponse react(UserPrincipal principal, UUID statusId, StatusReactionType type) {
        Status status = statusRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));

        User actor = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        StatusReaction reaction = statusReactionRepository.findByStatusIdAndActorId(statusId, principal.getId())
                .orElseGet(() -> StatusReaction.builder().status(status).actor(actor).build());

        boolean isNewReaction = reaction.getId() == null;
        reaction.setType(type);
        statusReactionRepository.save(reaction);

        if (isNewReaction) {
            notificationService.notify(status.getUser(), actor, NotificationType.NEW_STATUS_REACTION, null);
        }

        long count = statusReactionRepository.countByStatusId(statusId);
        return toResponse(status, count, type.name());
    }

    @Transactional
    public StatusResponse removeReaction(UserPrincipal principal, UUID statusId) {
        Status status = statusRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));

        StatusReaction reaction = statusReactionRepository.findByStatusIdAndActorId(statusId, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You hadn't reacted to this status"));

        statusReactionRepository.delete(reaction);

        long count = statusReactionRepository.countByStatusId(statusId);
        return toResponse(status, count, null);
    }

    private List<StatusResponse> enrichAndMap(UserPrincipal principal, List<Status> statuses) {
        List<UUID> statusIds = statuses.stream().map(Status::getId).toList();

        Map<UUID, Long> reactionCounts = statusIds.isEmpty()
                ? Map.of()
                : statusReactionRepository.countGroupedByStatusIds(statusIds).stream()
                .collect(Collectors.toMap(StatusReactionCountProjection::getStatusId, StatusReactionCountProjection::getReactionCount));

        Map<UUID, String> myReactions = statusIds.isEmpty()
                ? Map.of()
                : statusReactionRepository.findByActorIdAndStatusIds(principal.getId(), statusIds).stream()
                .collect(Collectors.toMap(r -> r.getStatus().getId(), r -> r.getType().name()));

        return statuses.stream()
                .map(status -> toResponse(
                        status,
                        reactionCounts.getOrDefault(status.getId(), 0L),
                        myReactions.get(status.getId())
                ))
                .toList();
    }

    private StatusResponse toResponse(Status status, long reactionCount, String reactedByCurrentUser) {
        User user = status.getUser();
        UserSummary userSummary = new UserSummary(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl()
        );

        return new StatusResponse(
                status.getId(),
                userSummary,
                status.getMood().name(),
                status.getCreatedAt(),
                status.getExpiresAt(),
                reactionCount,
                reactedByCurrentUser
        );
    }
}