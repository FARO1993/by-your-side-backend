package com.byyourside.backend.notification;

import com.byyourside.backend.notification.dto.NotificationResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // Metodo interno, llamado desde otros services (follow, comment, support)
    // cuando ocurre la accion correspondiente -- no expuesto via controller.
    @Transactional
    public void notify(User recipient, User actor, NotificationType type, UUID postId) {
        // Nunca te notificas a vos mismo (ej. comentar tu propio post).
        if (recipient.getId().equals(actor.getId())) {
            return;
        }

        notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .actor(actor)
                .type(type)
                .postId(postId)
                .build());
    }

    public Page<NotificationResponse> getNotifications(UUID recipientId, Pageable pageable) {
        return notificationRepository.findByRecipientId(recipientId, pageable)
                .map(this::toResponse);
    }

    public long getUnreadCount(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndReadFalse(recipientId);
    }

    @Transactional
    public void markAllAsRead(UUID recipientId) {
        notificationRepository.markAllAsRead(recipientId);
    }

    private NotificationResponse toResponse(Notification notification) {
        User actor = notification.getActor();
        UserSummary actorSummary = new UserSummary(
                actor.getId(), actor.getUsername(), actor.getDisplayName(), actor.getAvatarUrl()
        );

        return new NotificationResponse(
                notification.getId(),
                actorSummary,
                notification.getType().name(),
                notification.getPostId(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}