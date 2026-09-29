package com.byyourside.backend.availability.dto;

import com.byyourside.backend.availability.CompanionIntent;
import jakarta.validation.constraints.NotNull;

// Backend Debt B4B.3: LEGACY -- contrato historico de POST
// /api/availability. Traducido a OfferingType por AvailabilityController
// via LegacyAvailabilityMapper antes de llegar a CompanionOfferingService.
public record SetAvailabilityRequest(
        @NotNull
        CompanionIntent intent
) {
}