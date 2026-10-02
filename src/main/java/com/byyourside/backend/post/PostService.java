package com.byyourside.backend.post;

import com.byyourside.backend.block.BlockPolicy;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.post.dto.CreatePostRequest;
import com.byyourside.backend.post.dto.PostResponse;
import com.byyourside.backend.post.dto.UpdatePostRequest;
import com.byyourside.backend.postresponse.PostResponseCountProjection;
import com.byyourside.backend.postresponse.PostResponseRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    // Posts anonimos por persona en 24hs moviles (incluye borrados).
    static final int ANONYMOUS_DAILY_LIMIT = 3;

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PostResponseRepository postResponseRepository;
    private final PostAccessPolicy postAccessPolicy;
    private final BlockPolicy blockPolicy;

    @Transactional
    public PostResponse createPost(UserPrincipal principal, CreatePostRequest request) {
        User author = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        PostVisibility visibility = request.visibility() != null ? request.visibility() : PostVisibility.PUBLIC;
        boolean anonymous = Boolean.TRUE.equals(request.anonymous());
        if (anonymous) {
            // Anonimo + FOLLOWERS_ONLY/PRIVATE no tiene sentido: el espacio
            // anonimo es abierto, y restringirlo a seguidores lo des-anonimiza.
            if (visibility != PostVisibility.PUBLIC) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anonymous posts must be PUBLIC");
            }
            Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
            if (postRepository.countByAuthorIdAndAnonymousTrueAndCreatedAtAfter(author.getId(), since)
                    >= ANONYMOUS_DAILY_LIMIT) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Anonymous post limit reached");
            }
        }

        Post post = Post.builder()
                .author(author)
                .content(request.content())
                .visibility(visibility)
                .contentWarning(Boolean.TRUE.equals(request.contentWarning()))
                .anonymous(anonymous)
                .build();

        post = postRepository.save(post);
        // Post recien creado: nunca puede tener respuestas todavia.
        return toResponse(post, principal.getId(), Set.of(), Map.of(), Map.of());
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
            if (post.isAnonymous() && request.visibility() != PostVisibility.PUBLIC) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anonymous posts must be PUBLIC");
            }
            post.setVisibility(request.visibility());
        }
        if (request.contentWarning() != null) {
            post.setContentWarning(request.contentWarning());
        }

        post = postRepository.save(post);

        // Editar no reinicia las respuestas que ya tenia el post: las
        // consultamos reales, mismo criterio que antes con supportCount.
        Map<UUID, PostResponseCountProjection> counts = countsByPostId(List.of(postId));
        Map<UUID, String> currentUserTypes = currentUserTypesByPostId(principal.getId(), List.of(postId));

        return toResponse(post, principal.getId(), Set.of(), counts, currentUserTypes);
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

        Page<Post> postsPage = postRepository.findFeedForUser(feedAuthorIds, principal.getId(), pageable);

        return enrichAndMap(principal, postsPage);
    }

    // Espacio anonimo (V20): ver PostRepository#findAnonymousFeed.
    public Page<PostResponse> getAnonymousFeed(UserPrincipal principal, Pageable pageable) {
        return enrichAndMap(principal, postRepository.findAnonymousFeed(principal.getId(), pageable));
    }

    public Page<PostResponse> getUserPosts(UserPrincipal principal, UUID authorId, Pageable pageable) {
        if (!userRepository.existsById(authorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        boolean isOwner = principal.getId().equals(authorId);

        // Fase 9.4: si hay un bloqueo entre viewer/target, no hay posts que
        // ver -- pagina vacia (mismo 200 OK que un perfil PRIVATE sin
        // acceso, nunca 404: no confirmamos ni negamos el bloqueo via status
        // code aca). Un solo chequeo bilateral antes de tocar la query
        // principal, en vez de embeber el OR de bloqueo en el JPQL de
        // findVisiblePostsByAuthor -- mas eficiente para el caso de un solo
        // autor (evita correr la query de posts directamente) y no requiere
        // duplicar el join de UserBlock en esa query.
        if (!isOwner && blockPolicy.isBlockedBetween(principal.getId(), authorId)) {
            return Page.empty(pageable);
        }

        boolean isFollower = isOwner
                || followRepository.existsByFollowerIdAndFollowingId(principal.getId(), authorId);

        // Si el perfil del autor es PRIVATE y el viewer no es el dueno, la
        // query no devuelve ninguna fila (ver PostRepository) -- lista
        // vacia, 200 OK, nunca 404: la existencia del usuario ya se
        // confirmo arriba.
        Page<Post> postsPage = postRepository.findVisiblePostsByAuthor(authorId, isFollower, isOwner, pageable);

        Set<UUID> followedAuthorIds = isFollower ? Set.of(authorId) : Set.of();
        return enrichAndMap(principal, postsPage, followedAuthorIds);
    }

    public PostResponse getPost(UserPrincipal principal, UUID postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        // 404, no 403: no revelamos que un post existe (ni que es privado, ni
        // que su autor tiene el perfil en privado) si quien pregunta no
        // tiene permiso para verlo.
        if (!postAccessPolicy.canView(principal.getId(), post)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        UUID authorId = post.getAuthor().getId();
        boolean isOwner = principal.getId().equals(authorId);
        boolean isFollower = isOwner
                || followRepository.existsByFollowerIdAndFollowingId(principal.getId(), authorId);

        Map<UUID, PostResponseCountProjection> counts = countsByPostId(List.of(postId));
        Map<UUID, String> currentUserTypes = currentUserTypesByPostId(principal.getId(), List.of(postId));

        return toResponse(post,
                principal.getId(),
                isFollower ? Set.of(authorId) : Set.of(),
                counts,
                currentUserTypes);
    }

    // Version para getFeed: calcula "seguido" por autor real, ademas de las respuestas.
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
    // resuelve las respuestas en batch para toda la pagina -- 2 queries para
    // toda la pagina (conteos agregados + mi propio tipo por post), nunca una
    // consulta por post (ver PostResponseRepository).
    private Page<PostResponse> enrichAndMap(UserPrincipal principal, Page<Post> postsPage, Set<UUID> followedAuthorIds) {
        List<UUID> postIds = postsPage.getContent().stream().map(Post::getId).toList();

        Map<UUID, PostResponseCountProjection> counts = countsByPostId(postIds);
        Map<UUID, String> currentUserTypes = currentUserTypesByPostId(principal.getId(), postIds);

        return postsPage.map(post -> toResponse(post, principal.getId(), followedAuthorIds, counts, currentUserTypes));
    }

    private Map<UUID, PostResponseCountProjection> countsByPostId(List<UUID> postIds) {
        return postIds.isEmpty()
                ? Map.of()
                : postResponseRepository.countGroupedByPostIds(postIds).stream()
                .collect(Collectors.toMap(PostResponseCountProjection::getPostId, c -> c));
    }

    private Map<UUID, String> currentUserTypesByPostId(UUID currentUserId, List<UUID> postIds) {
        return postIds.isEmpty()
                ? Map.of()
                : postResponseRepository.findByUserIdAndPostIds(currentUserId, postIds).stream()
                .collect(Collectors.toMap(r -> r.getPost().getId(), r -> r.getType().name()));
    }

    // UNICO lugar que arma un PostResponse: aca se decide si se expone el
    // autor. En un post anonimo, para cualquiera que no sea el autor, `author`
    // viaja en null y `followedByCurrentUser` en false.
    private PostResponse toResponse(Post post, UUID viewerId, Set<UUID> followedAuthorIds,
                                    Map<UUID, PostResponseCountProjection> countsByPost,
                                    Map<UUID, String> currentUserTypeByPost) {
        User author = post.getAuthor();
        boolean hideAuthor = post.isAnonymous() && !author.getId().equals(viewerId);
        UserSummary authorSummary = hideAuthor ? null : new UserSummary(
                author.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.getAvatarUrl()
        );

        PostResponseCountProjection counts = countsByPost.get(post.getId());
        long presenceCount = counts == null ? 0 : counts.getPresenceCount();
        long listeningCount = counts == null ? 0 : counts.getListeningCount();
        String currentUserResponseType = currentUserTypeByPost.get(post.getId());

        return new PostResponse(
                post.getId(),
                authorSummary,
                post.getContent(),
                post.getVisibility().name(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                !hideAuthor && followedAuthorIds.contains(author.getId()),
                presenceCount + listeningCount,
                currentUserResponseType != null,
                presenceCount,
                listeningCount,
                currentUserResponseType,
                post.isContentWarning(),
                post.isAnonymous()
        );
    }

}
