package com.byyourside.backend.status.dto;

import com.byyourside.backend.status.StatusReactionType;
import jakarta.validation.constraints.NotNull;

public record ReactToStatusRequest(
        @NotNull
        StatusReactionType type
) {
}