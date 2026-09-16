package com.byyourside.backend.post.dto;

import com.byyourside.backend.post.PostVisibility;
import jakarta.validation.constraints.Size;

public record UpdatePostRequest(

        @Size(max = 2000, message = "Post content must be at most 2000 characters long")
        String content,

        PostVisibility visibility
) {
}