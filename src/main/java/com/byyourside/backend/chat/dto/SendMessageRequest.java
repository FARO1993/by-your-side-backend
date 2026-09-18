package com.byyourside.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank
        @Size(max = 2000, message = "Message must be at most 2000 characters long")
        String content
) {
}