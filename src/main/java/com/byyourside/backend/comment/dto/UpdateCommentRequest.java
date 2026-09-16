package com.byyourside.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommentRequest(

        @NotBlank
        @Size(max = 500, message = "Comment must be at most 500 characters long")
        String content
) {
}