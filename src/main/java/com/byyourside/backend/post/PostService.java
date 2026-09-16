package com.byyourside.backend.post;

import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.post.dto.CreatePostRequest;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    @Transactional
    public PostResponse createPost(UserPrincipal principal, CreatePostRequest request) {
        User author = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Post post = Post.builder()
                .author(author)
                .content(request.content())
                .visibility(request.visibility() != null ? request.visibility() : PostVisibility.PUBLIC)
                .build();

        post = postRepository.save(post);
        return toResponse(post);
    }

    public Page<PostResponse> getFeed(UserPrincipal principal, Pageable pageable) {
        List<UUID> feedAuthorIds = followRepository.findByFollowerId(principal.getId()).stream()
                .map(follow -> follow.getFollowing().getId())
                .collect(Collectors.toList());

        // El feed incluye tus propios posts, ademas de los de la gente que seguis.
        feedAuthorIds.add(principal.getId());

        return postRepository.findFeedForUser(feedAuthorIds, pageable)
                .map(this::toResponse);
    }

    private PostResponse toResponse(Post post) {
        User author = post.getAuthor();
        UserSummary authorSummary = new UserSummary(
                author.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.getAvatarUrl()
        );

        return new PostResponse(
                post.getId(),
                authorSummary,
                post.getContent(),
                post.getVisibility().name(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}