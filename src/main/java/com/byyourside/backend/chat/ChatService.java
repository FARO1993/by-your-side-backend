package com.byyourside.backend.chat;

import com.byyourside.backend.availability.AvailabilityRepository;
import com.byyourside.backend.chat.dto.ConversationResponse;
import com.byyourside.backend.chat.dto.MessageResponse;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AvailabilityRepository availabilityRepository;

    @Transactional
    public ConversationResponse getOrCreateConversation(UserPrincipal principal, UUID otherUserId) {
        if (principal.getId().equals(otherUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot start a conversation with yourself");
        }

        User currentUser = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found"));

        boolean connected = followRepository.existsByFollowerIdAndFollowingId(currentUser.getId(), otherUser.getId())
                || followRepository.existsByFollowerIdAndFollowingId(otherUser.getId(), currentUser.getId());

        // El modo compañia permite el primer contacto sin follow previo,
        // pero solo si la otra persona dio consentimiento explicito y
        // publico declarandose disponible para acompañar en este momento.
        // Nunca al reves: nadie puede mensajear a alguien "disponible"
        // sin que esa disponibilidad este activa ahora mismo.
        boolean targetIsAvailableForCompanionship =
                availabilityRepository.existsByUserIdAndExpiresAtAfter(otherUser.getId(), Instant.now());

        if (!connected && !targetIsAvailableForCompanionship) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only message users you follow, who follow you, or who are currently available for companionship");
        }

        User userA = currentUser.getId().toString().compareTo(otherUser.getId().toString()) <= 0 ? currentUser : otherUser;
        User userB = userA.equals(currentUser) ? otherUser : currentUser;

        Conversation conversation = conversationRepository.findByUserAIdAndUserBId(userA.getId(), userB.getId())
                .orElseGet(() -> conversationRepository.save(Conversation.builder()
                        .userA(userA)
                        .userB(userB)
                        .build()));

        return toConversationResponse(conversation, principal.getId(), 0);
    }

    public List<ConversationResponse> getConversations(UserPrincipal principal) {
        List<Conversation> conversations = conversationRepository.findByParticipant(principal.getId());

        List<UUID> conversationIds = conversations.stream().map(Conversation::getId).toList();
        Map<UUID, Long> unreadCounts = conversationIds.isEmpty()
                ? Map.of()
                : messageRepository.countUnreadGroupedByConversation(conversationIds, principal.getId()).stream()
                .collect(Collectors.toMap(UnreadCountProjection::getConversationId, UnreadCountProjection::getUnreadCount));

        return conversations.stream()
                .map(c -> toConversationResponse(c, principal.getId(), unreadCounts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public Page<MessageResponse> getMessages(UserPrincipal principal, UUID conversationId, Pageable pageable) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        ensureParticipant(conversation, principal.getId());

        Page<Message> messages = messageRepository.findByConversationId(conversationId, pageable);
        messageRepository.markAsRead(conversationId, principal.getId());

        return messages.map(this::toMessageResponse);
    }

    @Transactional
    public MessageResponse sendMessage(UserPrincipal principal, UUID conversationId, String content) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        ensureParticipant(conversation, principal.getId());

        User sender = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Message message = messageRepository.save(Message.builder()
                .conversation(conversation)
                .sender(sender)
                .content(content)
                .build());

        conversation.setLastMessageContent(content);
        conversation.setLastMessageAt(message.getCreatedAt());
        conversationRepository.save(conversation);

        MessageResponse response = toMessageResponse(message);

        User recipient = conversation.otherParticipant(principal.getId());
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), "/queue/messages", response);

        return response;
    }

    private void ensureParticipant(Conversation conversation, UUID userId) {
        if (!conversation.hasParticipant(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not part of this conversation");
        }
    }

    private ConversationResponse toConversationResponse(Conversation conversation, UUID currentUserId, long unreadCount) {
        User other = conversation.otherParticipant(currentUserId);
        UserSummary otherSummary = new UserSummary(
                other.getId(), other.getUsername(), other.getDisplayName(), other.getAvatarUrl()
        );

        return new ConversationResponse(
                conversation.getId(),
                otherSummary,
                conversation.getLastMessageContent(),
                conversation.getLastMessageAt(),
                unreadCount
        );
    }

    private MessageResponse toMessageResponse(Message message) {
        User sender = message.getSender();
        UserSummary senderSummary = new UserSummary(
                sender.getId(), sender.getUsername(), sender.getDisplayName(), sender.getAvatarUrl()
        );

        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                senderSummary,
                message.getContent(),
                message.isRead(),
                message.getCreatedAt()
        );
    }
}