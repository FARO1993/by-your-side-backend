package com.byyourside.backend.comment;

import com.byyourside.backend.comment.dto.CommentResponse;
import com.byyourside.backend.comment.dto.CreateCommentRequest;
import com.byyourside.backend.comment.dto.UpdateCommentRequest;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @Transactional
    public CommentResponse createComment(UserPrincipal principal, UUID postId, CreateCommentRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        User author = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Comment comment = Comment.builder()
                .post(post)
                .author(author)
                .content(request.content())
                .build();

        comment = commentRepository.save(comment);
        return toResponse(comment);
    }

    public List<CommentResponse> getComments(UUID postId) {
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        return commentRepository.findVisibleCommentsByPostId(postId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CommentResponse updateComment(UserPrincipal principal, UUID postId, UUID commentId, UpdateCommentRequest request) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        if (!comment.getPost().getId().equals(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        if (!comment.getAuthor().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit your own comments");
        }

        comment.setContent(request.content());
        comment = commentRepository.save(comment);
        return toResponse(comment);
    }

    @Transactional
    public void deleteComment(UserPrincipal principal, UUID postId, UUID commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        if (!comment.getPost().getId().equals(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
        }

        boolean isAuthor = comment.getAuthor().getId().equals(principal.getId());
        boolean isModerator = principal.getRole().equals("MODERATOR") || principal.getRole().equals("ADMIN");

        if (!isAuthor && !isModerator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have permission to delete this comment");
        }

        comment.setStatus(CommentStatus.REMOVED);
        commentRepository.save(comment);
    }

    private CommentResponse toResponse(Comment comment) {
        User author = comment.getAuthor();
        UserSummary authorSummary = new UserSummary(
                author.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.getAvatarUrl()
        );

        return new CommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                authorSummary,
                comment.getContent(),
                comment.getCreatedAt()
        );
    }
}