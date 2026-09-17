package com.byyourside.backend.status;

import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.status.dto.ReactToStatusRequest;
import com.byyourside.backend.status.dto.SetStatusRequest;
import com.byyourside.backend.status.dto.StatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/statuses")
@RequiredArgsConstructor
public class StatusController {

    private final StatusService statusService;

    @PostMapping
    public ResponseEntity<StatusResponse> setStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                    @Valid @RequestBody SetStatusRequest request) {
        StatusResponse response = statusService.setStatus(principal, request.mood());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/feed")
    public ResponseEntity<List<StatusResponse>> getFeed(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(statusService.getFeed(principal));
    }

    @PostMapping("/{statusId}/react")
    public ResponseEntity<StatusResponse> react(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable UUID statusId,
                                                @Valid @RequestBody ReactToStatusRequest request) {
        return ResponseEntity.ok(statusService.react(principal, statusId, request.type()));
    }

    @DeleteMapping("/{statusId}/react")
    public ResponseEntity<StatusResponse> removeReaction(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID statusId) {
        return ResponseEntity.ok(statusService.removeReaction(principal, statusId));
    }
}