package com.byyourside.backend.availability;

import com.byyourside.backend.availability.dto.AvailabilityResponse;
import com.byyourside.backend.availability.dto.SetAvailabilityRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    @PostMapping
    public ResponseEntity<AvailabilityResponse> setAvailability(@AuthenticationPrincipal UserPrincipal principal,
                                                                @Valid @RequestBody SetAvailabilityRequest request) {
        AvailabilityResponse response = availabilityService.setAvailability(principal, request.intent());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelAvailability(@AuthenticationPrincipal UserPrincipal principal) {
        availabilityService.cancelAvailability(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public ResponseEntity<AvailabilityResponse> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(availabilityService.getMine(principal));
    }

    @GetMapping
    public ResponseEntity<List<AvailabilityResponse>> listAvailable(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @RequestParam CompanionIntent intent) {
        return ResponseEntity.ok(availabilityService.listAvailable(principal, intent));
    }
}