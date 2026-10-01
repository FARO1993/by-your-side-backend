package com.byyourside.backend.follow;

import com.byyourside.backend.follow.dto.FollowRequestResponse;
import com.byyourside.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/follow-requests")
@RequiredArgsConstructor
public class FollowRequestController {

    private final FollowRequestService followRequestService;

    @PostMapping("/{requestId}/accept")
    public ResponseEntity<FollowRequestResponse> accept(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID requestId) {
        return ResponseEntity.ok(followRequestService.accept(principal, requestId));
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<FollowRequestResponse> reject(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID requestId) {
        return ResponseEntity.ok(followRequestService.reject(principal, requestId));
    }

    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable UUID requestId) {
        followRequestService.cancel(principal, requestId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/incoming")
    public ResponseEntity<List<FollowRequestResponse>> incoming(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(followRequestService.getIncoming(principal.getId()));
    }

    @GetMapping("/outgoing")
    public ResponseEntity<List<FollowRequestResponse>> outgoing(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(followRequestService.getOutgoing(principal.getId()));
    }
}
