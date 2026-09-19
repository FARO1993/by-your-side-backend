package com.byyourside.backend.availability.dto;

import com.byyourside.backend.availability.CompanionIntent;
import jakarta.validation.constraints.NotNull;

public record SetAvailabilityRequest(
        @NotNull
        CompanionIntent intent
) {
}