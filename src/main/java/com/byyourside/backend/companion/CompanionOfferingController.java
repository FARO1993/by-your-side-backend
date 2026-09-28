package com.byyourside.backend.companion;

import com.byyourside.backend.companion.dto.CompanionCandidateResponse;
import com.byyourside.backend.companion.dto.CompanionOfferingResponse;
import com.byyourside.backend.companion.dto.SetCompanionOfferingRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companion/offering")
@RequiredArgsConstructor
public class CompanionOfferingController {

    private final CompanionOfferingService companionOfferingService;

    @PutMapping
    public ResponseEntity<CompanionOfferingResponse> setOffering(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @Valid @RequestBody SetCompanionOfferingRequest request) {
        CompanionOfferingResponse response = companionOfferingService.setOffering(principal, request.type());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelOffering(@AuthenticationPrincipal UserPrincipal principal) {
        companionOfferingService.cancelOffering(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public ResponseEntity<CompanionOfferingResponse> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(companionOfferingService.getMine(principal));
    }

    @GetMapping
    public ResponseEntity<List<CompanionCandidateResponse>> searchByType(@AuthenticationPrincipal UserPrincipal principal,
                                                                          @RequestParam OfferingType type) {
        return ResponseEntity.ok(companionOfferingService.searchByType(principal, type));
    }

    @GetMapping("/compatible")
    public ResponseEntity<List<CompanionCandidateResponse>> searchCompatible(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(companionOfferingService.searchCompatibleWithMyNeed(principal));
    }
}
