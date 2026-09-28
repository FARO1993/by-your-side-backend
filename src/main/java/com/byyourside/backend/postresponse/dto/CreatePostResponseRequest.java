package com.byyourside.backend.postresponse.dto;

import com.byyourside.backend.postresponse.PostResponseType;
import jakarta.validation.constraints.NotNull;

public record CreatePostResponseRequest(
        @NotNull
        PostResponseType type
) {
}
