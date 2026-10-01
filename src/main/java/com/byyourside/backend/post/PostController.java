package com.byyourside.backend.post;

import com.byyourside.backend.post.dto.CreatePostRequest;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.post.dto.UpdatePostRequest;
import com.byyourside.backend.postresponse.PostResponseService;
import com.byyourside.backend.postresponse.dto.CreatePostResponseRequest;
import com.byyourside.backend.postresponse.dto.PostResponseSummaryResponse;
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

    private final PostResponseService postResponseService;

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

    // Backend Debt B1 -- endpoint nuevo: upsert real (crea o cambia de tipo,
    // idempotente si es el mismo tipo). El autor no puede responder a su
    // propio post.
    @PutMapping("/{postId}/response")
    public ResponseEntity<PostResponseSummaryResponse> upsertResponse(@AuthenticationPrincipal UserPrincipal principal,
                                                                       @PathVariable UUID postId,
                                                                       @Valid @RequestBody CreatePostResponseRequest request) {
        return ResponseEntity.ok(postResponseService.upsertResponse(principal, postId, request.type()));
    }

    @DeleteMapping("/{postId}/response")
    public ResponseEntity<PostResponseSummaryResponse> deleteResponse(@AuthenticationPrincipal UserPrincipal principal,
                                                                       @PathVariable UUID postId) {
        return ResponseEntity.ok(postResponseService.deleteResponse(principal, postId));
    }

    // LEGACY (Backend Debt B1) -- preservado por compatibilidad temporal,
    // opera sobre la misma fila que /response. Preferir PUT/DELETE
    // /{postId}/response en integraciones nuevas. Ver docs/API_CONTRACT.md.
    @PostMapping("/{postId}/support")
    public ResponseEntity<PostResponseSummaryResponse> supportPost(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PathVariable UUID postId) {
        PostResponseSummaryResponse response = postResponseService.addLegacySupport(principal, postId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{postId}/support")
    public ResponseEntity<PostResponseSummaryResponse> unsupportPost(@AuthenticationPrincipal UserPrincipal principal,
                                                                      @PathVariable UUID postId) {
        return ResponseEntity.ok(postResponseService.removeLegacySupport(principal, postId));
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostResponse> getPost(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable UUID postId) {
        return ResponseEntity.ok(postService.getPost(principal, postId));
    }
}
