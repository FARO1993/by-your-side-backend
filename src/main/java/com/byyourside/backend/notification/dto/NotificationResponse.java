package com.byyourside.backend.notification.dto;

import com.byyourside.backend.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

// Backend Debt B3: `statusId`/`followRequestId` son campos nuevos (adicion
// pura al final, no rompe contrato) -- mismo DTO para REST y WebSocket
// (NotificationService.notify() usa este mismo record para ambos), asi que
// quedan disponibles en los dos canales sin trabajo adicional. Ambos viajan
// en null salvo para su NotificationType correspondiente (statusId solo en
// NEW_STATUS_REACTION; followRequestId en FOLLOW_REQUEST_RECEIVED/
// FOLLOW_REQUEST_ACCEPTED) -- nunca reusar postId para status ni viceversa.
public record NotificationResponse(
        UUID id,
        UserSummary actor,
        String type,
        UUID postId,
        UUID statusId,
        UUID followRequestId,
        boolean read,
        Instant createdAt
) {
}
