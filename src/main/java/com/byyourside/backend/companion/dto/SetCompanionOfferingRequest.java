package com.byyourside.backend.companion.dto;

import com.byyourside.backend.companion.OfferingType;
import jakarta.validation.constraints.NotNull;

public record SetCompanionOfferingRequest(
        @NotNull
        OfferingType type
) {
}
