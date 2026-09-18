package com.byyourside.backend.status.dto;

import com.byyourside.backend.status.StatusMood;
import jakarta.validation.constraints.NotNull;

public record SetStatusRequest(
        @NotNull
        StatusMood mood
) {
}