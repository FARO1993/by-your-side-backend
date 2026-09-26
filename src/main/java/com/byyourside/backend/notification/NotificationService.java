package com.byyourside.backend.notification;

import com.byyourside.backend.block.BlockPolicy;
import com.byyourside.backend.notification.dto.NotificationResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BlockPolicy blockPolicy;

    // Metodo interno, llamado desde otros services (follow, comment, support)
    // cuando ocurre la accion correspondiente -- no expuesto via controller.
    //
    // Fase 9.4: guarda centralizada -- si hay un bloqueo entre recipient/
    // actor, la notificacion NUEVA ni se crea ni se empuja por WS. Esto NO
    // toca notificaciones historicas ya existentes (esas ya estan guardadas
    // antes de que el bloqueo exista, y este metodo solo corre para
    // notificaciones nuevas). Todos los llamadores actuales de notify() son
    // interacciones usuario-a-usuario (follow, comentario, apoyo, reaccion de
    // estado, follow request) -- no existe hoy un tipo de notificacion de
    // sistema/admin en este esquema, asi que no hace falta (todavia) una
    // excepcion para no silenciar avisos administrativos.
    @Transactional
    public void notify(User recipient, User actor, NotificationType type, UUID postId) {
        if (recipient.getId().equals(actor.getId())) {
            return;
        }
        if (blockPolicy.isBlockedBetween(recipient.getId(), actor.getId())) {
            return;
        }

        Notification notification = notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .actor(actor)
                .type(type)
                .postId(postId)
                .build());

        NotificationResponse response = toResponse(notification);
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), "/queue/notifications", response);
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