package com.byyourside.backend.companion.dto;

import com.byyourside.backend.companion.NeedType;
import jakarta.validation.constraints.NotNull;

public record SetCompanionNeedRequest(
        @NotNull
        NeedType type
) {
}
