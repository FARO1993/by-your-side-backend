package com.byyourside.backend.post;

import com.byyourside.backend.post.dto.CreatePostRequest;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.post.dto.UpdatePostRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @PostMapping
    public ResponseEntity<PostResponse> createPost(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody CreatePostRequest request) {
        PostResponse response = postService.createPost(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/feed")
    public ResponseEntity<Page<PostResponse>> getFeed(@AuthenticationPrincipal UserPrincipal principal,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(postService.getFeed(principal, pageable));
    }

    @PatchMapping("/{postId}")
    public ResponseEntity<PostResponse> updatePost(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable UUID postId,
                                                   @Valid @RequestBody UpdatePostRequest request) {
        return ResponseEntity.ok(postService.updatePost(principal, postId, request));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable UUID postId) {
        postService.deletePost(principal, postId);
        return ResponseEntity.noContent().build();
    }
}