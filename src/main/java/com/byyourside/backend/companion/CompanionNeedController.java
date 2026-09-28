package com.byyourside.backend.companion;

import com.byyourside.backend.companion.dto.CompanionNeedResponse;
import com.byyourside.backend.companion.dto.SetCompanionNeedRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companion/need")
@RequiredArgsConstructor
public class CompanionNeedController {

    private final CompanionNeedService companionNeedService;

    @PutMapping
    public ResponseEntity<CompanionNeedResponse> setNeed(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody SetCompanionNeedRequest request) {
        CompanionNeedResponse response = companionNeedService.setNeed(principal, request.type());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelNeed(@AuthenticationPrincipal UserPrincipal principal) {
        companionNeedService.cancelNeed(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public ResponseEntity<CompanionNeedResponse> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(companionNeedService.getMine(principal));
    }
}
