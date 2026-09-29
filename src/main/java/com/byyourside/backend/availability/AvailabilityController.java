package com.byyourside.backend.availability;

import com.byyourside.backend.availability.dto.AvailabilityResponse;
import com.byyourside.backend.availability.dto.SetAvailabilityRequest;
import com.byyourside.backend.companion.CompanionCandidateProjection;
import com.byyourside.backend.companion.CompanionOfferingService;
import com.byyourside.backend.companion.OfferingType;
import com.byyourside.backend.companion.dto.CompanionOfferingResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// Backend Debt B4B.3: LEGACY ADAPTER -- mantiene exactamente el contrato
// historico de /api/availability/** (path, auth, shape de request/
// response), pero delega toda la logica real en CompanionOfferingService.
// companion_offerings es la UNICA fuente de verdad; esta clase nunca lee/
// escribe la tabla `availabilities` (retirada en V16) ni usa
// AvailabilityService/AvailabilityRepository (eliminados en este mismo
// PR). CompanionIntent <-> OfferingType se traduce en
// LegacyAvailabilityMapper (el sentido OfferingType -> CompanionIntent es
// deliberadamente lossy, ver ahi el detalle). Sin fecha de retiro todavia
// -- nuevos clientes deben usar /api/companion/offering/** (ver
// API_CONTRACT.md).
@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final CompanionOfferingService companionOfferingService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<AvailabilityResponse> setAvailability(@AuthenticationPrincipal UserPrincipal principal,
                                                                @Valid @RequestBody SetAvailabilityRequest request) {
        OfferingType offeringType = LegacyAvailabilityMapper.toOfferingType(request.intent());
        CompanionOfferingResponse offering = companionOfferingService.setOffering(principal, offeringType);
        return ResponseEntity.status(HttpStatus.CREATED).body(toLegacyResponse(offering, principal));
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelAvailability(@AuthenticationPrincipal UserPrincipal principal) {
        companionOfferingService.cancelOffering(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public ResponseEntity<AvailabilityResponse> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        CompanionOfferingResponse offering = companionOfferingService.getMine(principal);
        return ResponseEntity.ok(offering == null ? null : toLegacyResponse(offering, principal));
    }

    @GetMapping
    public ResponseEntity<List<AvailabilityResponse>> listAvailable(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @RequestParam CompanionIntent intent) {
        OfferingType offeringType = LegacyAvailabilityMapper.toOfferingType(intent);
        List<AvailabilityResponse> results = companionOfferingService.searchCandidatesRaw(principal, offeringType)
                .stream()
                .map(this::toLegacyResponse)
                .toList();
        return ResponseEntity.ok(results);
    }

    // "Mia" -- el owner es siempre el principal autenticado, nunca viene del
    // body/path. Un solo lookup extra por PK (indexado) para armar el
    // UserSummary que el shape legacy siempre incluyo -- CompanionOfferingResponse
    // (el DTO nuevo) deliberadamente no lo trae, ver B4B.1/B4B.2.
    private AvailabilityResponse toLegacyResponse(CompanionOfferingResponse offering, UserPrincipal principal) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        UserSummary userSummary = new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl());
        CompanionIntent intent = LegacyAvailabilityMapper.toCompanionIntent(OfferingType.valueOf(offering.type()));
        return new AvailabilityResponse(offering.id(), userSummary, intent.name(), offering.createdAt(), offering.expiresAt());
    }

    private AvailabilityResponse toLegacyResponse(CompanionCandidateProjection projection) {
        UserSummary userSummary = new UserSummary(
                projection.getUserId(), projection.getUsername(), projection.getDisplayName(), projection.getAvatarUrl()
        );
        CompanionIntent intent = LegacyAvailabilityMapper.toCompanionIntent(OfferingType.valueOf(projection.getOfferingType()));
        return new AvailabilityResponse(projection.getOfferingId(), userSummary, intent.name(),
                projection.getCreatedAt(), projection.getExpiresAt());
    }
}
