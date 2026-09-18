package com.byyourside.backend.comment;

import com.byyourside.backend.comment.dto.CommentResponse;
import com.byyourside.backend.comment.dto.CreateCommentRequest;
import com.byyourside.backend.comment.dto.UpdateCommentRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    public ResponseEntity<CommentResponse> createComment(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID postId,
                                                         @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse response = commentService.createComment(principal, postId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<CommentResponse>> getComments(@PathVariable UUID postId) {
        return ResponseEntity.ok(commentService.getComments(postId));
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<CommentResponse> updateComment(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID postId,
                                                         @PathVariable UUID commentId,
                                                         @Valid @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok(commentService.updateComment(principal, postId, commentId, request));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable UUID postId,
                                              @PathVariable UUID commentId) {
        commentService.deleteComment(principal, postId, commentId);
        return ResponseEntity.noContent().build();
    }
}