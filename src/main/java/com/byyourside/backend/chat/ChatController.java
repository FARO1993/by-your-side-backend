package com.byyourside.backend.chat;

import com.byyourside.backend.chat.dto.ConversationResponse;
import com.byyourside.backend.chat.dto.MessageResponse;
import com.byyourside.backend.chat.dto.SendMessageRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/{userId}")
    public ResponseEntity<ConversationResponse> getOrCreateConversation(@AuthenticationPrincipal UserPrincipal principal,
                                                                        @PathVariable UUID userId) {
        return ResponseEntity.ok(chatService.getOrCreateConversation(principal, userId));
    }

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getConversations(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(chatService.getConversations(principal));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<Page<MessageResponse>> getMessages(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable UUID conversationId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(chatService.getMessages(principal, conversationId, pageable));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID conversationId,
                                                       @Valid @RequestBody SendMessageRequest request) {
        MessageResponse response = chatService.sendMessage(principal, conversationId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}