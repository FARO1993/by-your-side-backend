package com.byyourside.backend.post.dto;

import com.byyourside.backend.post.PostVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(

        @NotBlank
        @Size(max = 2000, message = "Post content must be at most 2000 characters long")
        String content,

        // Opcional: si viene null, el service lo setea en PUBLIC por default.
        PostVisibility visibility
) {
}