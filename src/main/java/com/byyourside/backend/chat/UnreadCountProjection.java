package com.byyourside.backend.chat;

import java.util.UUID;

public interface UnreadCountProjection {
    UUID getConversationId();
    long getUnreadCount();
}