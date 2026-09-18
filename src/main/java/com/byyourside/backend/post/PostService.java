package com.byyourside.backend.post;

import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.post.dto.CreatePostRequest;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.post.dto.UpdatePostRequest;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.support.PostSupportCountProjection;
import com.byyourside.backend.support.PostSupportRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PostSupportRepository postSupportRepository;

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
        // Post recien creado: nunca puede tener apoyo todavia.
        return toResponse(post, Set.of(), Map.of(), Set.of());
    }

    @Transactional
    public PostResponse updatePost(UserPrincipal principal, UUID postId, UpdatePostRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        if (!post.getAuthor().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit your own posts");
        }

        if (request.content() != null) {
            post.setContent(request.content());
        }
        if (request.visibility() != null) {
            post.setVisibility(request.visibility());
        }

        post = postRepository.save(post);

        // Editar no reinicia el apoyo que ya tenia el post: lo consultamos real.
        long supportCount = postSupportRepository.countByPostId(postId);
        boolean supported = postSupportRepository.existsByPostIdAndUserId(postId, principal.getId());

        return toResponse(post, Set.of(), Map.of(postId, supportCount), supported ? Set.of(postId) : Set.of());
    }

    @Transactional
    public void deletePost(UserPrincipal principal, UUID postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        boolean isAuthor = post.getAuthor().getId().equals(principal.getId());
        boolean isModerator = principal.getRole().equals("MODERATOR") || principal.getRole().equals("ADMIN");

        if (!isAuthor && !isModerator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have permission to delete this post");
        }

        post.setStatus(PostStatus.REMOVED);
        postRepository.save(post);
    }

    public Page<PostResponse> getFeed(UserPrincipal principal, Pageable pageable) {
        List<UUID> feedAuthorIds = followRepository.findByFollowerId(principal.getId()).stream()
                .map(follow -> follow.getFollowing().getId())
                .collect(Collectors.toList());

        feedAuthorIds.add(principal.getId());

        Page<Post> postsPage = postRepository.findFeedForUser(feedAuthorIds, pageable);

        return enrichAndMap(principal, postsPage);
    }

    public Page<PostResponse> getUserPosts(UserPrincipal principal, UUID authorId, Pageable pageable) {
        if (!userRepository.existsById(authorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        boolean isOwner = principal.getId().equals(authorId);
        boolean isFollower = isOwner
                || followRepository.existsByFollowerIdAndFollowingId(principal.getId(), authorId);

        Page<Post> postsPage = postRepository.findVisiblePostsByAuthor(authorId, isFollower, isOwner, pageable);

        Set<UUID> followedAuthorIds = isFollower ? Set.of(authorId) : Set.of();
        return enrichAndMap(principal, postsPage, followedAuthorIds);
    }

    public PostResponse getPost(UserPrincipal principal, UUID postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        if (post.getStatus() != PostStatus.VISIBLE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        UUID authorId = post.getAuthor().getId();
        boolean isOwner = principal.getId().equals(authorId);
        boolean isFollower = isOwner
                || followRepository.existsByFollowerIdAndFollowingId(principal.getId(), authorId);

        boolean visible = switch (post.getVisibility()) {
            case PUBLIC -> true;
            case FOLLOWERS_ONLY -> isFollower;
            case PRIVATE -> isOwner;
        };

        // 404, no 403: no revelamos que un post privado existe si quien
        // pregunta no tiene permiso para verlo.
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        long supportCount = postSupportRepository.countByPostId(postId);
        boolean supported = postSupportRepository.existsByPostIdAndUserId(postId, principal.getId());

        return toResponse(post,
                isFollower ? Set.of(authorId) : Set.of(),
                Map.of(postId, supportCount),
                supported ? Set.of(postId) : Set.of());
    }

    // Version para getFeed: calcula "seguido" por autor real, ademas del apoyo.
    private Page<PostResponse> enrichAndMap(UserPrincipal principal, Page<Post> postsPage) {
        List<UUID> authorIdsInPage = postsPage.getContent().stream()
                .map(post -> post.getAuthor().getId())
                .distinct()
                .toList();

        Set<UUID> followedAuthorIds = authorIdsInPage.isEmpty()
                ? Set.of()
                : Set.copyOf(followRepository.findFollowingIdsAmong(principal.getId(), authorIdsInPage));

        return enrichAndMap(principal, postsPage, followedAuthorIds);
    }

    // Version compartida: recibe el set de "seguido" ya resuelto (getUserPosts
    // lo calcula distinto, ya que todos los posts son del mismo autor) y
    // resuelve el apoyo en batch para toda la pagina.
    private Page<PostResponse> enrichAndMap(UserPrincipal principal, Page<Post> postsPage, Set<UUID> followedAuthorIds) {
        List<UUID> postIds = postsPage.getContent().stream().map(Post::getId).toList();

        Map<UUID, Long> supportCounts = postIds.isEmpty()
                ? Map.of()
                : postSupportRepository.countGroupedByPostIds(postIds).stream()
                .collect(Collectors.toMap(PostSupportCountProjection::getPostId, PostSupportCountProjection::getSupportCount));

        Set<UUID> supportedPostIds = postIds.isEmpty()
                ? Set.of()
                : Set.copyOf(postSupportRepository.findSupportedPostIds(principal.getId(), postIds));

        return postsPage.map(post -> toResponse(post, followedAuthorIds, supportCounts, supportedPostIds));
    }

    private PostResponse toResponse(Post post, Set<UUID> followedAuthorIds,
                                    Map<UUID, Long> supportCounts, Set<UUID> supportedPostIds) {
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
                post.getUpdatedAt(),
                followedAuthorIds.contains(author.getId()),
                supportCounts.getOrDefault(post.getId(), 0L),
                supportedPostIds.contains(post.getId())
        );
    }

}