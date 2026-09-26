package com.byyourside.backend.follow;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
import com.byyourside.backend.user.ProfileVisibility;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import com.byyourside.backend.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Fase 9.3: follow requests para perfiles PRIVATE + gestion de followers.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FollowRequestIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User requester;
    private String requesterToken;
    private User target;
    private String targetToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();

        requester = registerUser("requester", "requester@example.com", ProfileVisibility.PUBLIC);
        requesterToken = login("requester");
        target = registerUser("target", "target@example.com", ProfileVisibility.PRIVATE);
        targetToken = login("target");
    }

    private User registerUser(String username, String email, ProfileVisibility visibility) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .profileVisibility(visibility)
                .build();
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = """
                {"email": "%s@example.com", "password": "secretpass123"}
                """.formatted(username);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private JsonNode sendFollowRequest() throws Exception {
        String response = mockMvc.perform(post("/api/follows/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private UUID sendFollowRequestAndGetId() throws Exception {
        return UUID.fromString(sendFollowRequest().get("requestId").asText());
    }

    // ============================================================
    // CORE (A-O)
    // ============================================================

    @Test
    void followingPublicProfile_createsFollowImmediately() throws Exception { // A
        User publicTarget = registerUser("pub", "pub@example.com", ProfileVisibility.PUBLIC);

        mockMvc.perform(post("/api/follows/{userId}", publicTarget.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followState").value("FOLLOWING"))
                .andExpect(jsonPath("$.requestId").doesNotExist());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(requester.getId(), publicTarget.getId())).isTrue();
    }

    @Test
    void followingPrivateProfile_createsPendingRequest_notFollow() throws Exception { // B, C
        mockMvc.perform(post("/api/follows/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followState").value("REQUESTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(requester.getId(), target.getId())).isFalse();
        assertThat(followRequestRepository.existsByRequesterIdAndTargetIdAndStatus(
                requester.getId(), target.getId(), FollowRequestStatus.PENDING)).isTrue();
    }

    @Test
    void followingPrivateProfileTwice_doesNotDuplicatePendingRequest() throws Exception { // D
        UUID firstId = sendFollowRequestAndGetId();
        UUID secondId = sendFollowRequestAndGetId();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(followRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void followingSelf_fails() throws Exception { // E
        mockMvc.perform(post("/api/follows/{userId}", requester.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot follow yourself"));
    }

    @Test
    void requesterCanCancelOwnPendingRequest() throws Exception { // F
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(delete("/api/follow-requests/{requestId}", requestId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNoContent());

        FollowRequest reloaded = followRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
    }

    @Test
    void otherUserCannotCancelSomeoneElsesRequest() throws Exception { // G
        UUID requestId = sendFollowRequestAndGetId();
        String otherToken = login("target"); // el target no es el requester

        mockMvc.perform(delete("/api/follow-requests/{requestId}", requestId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        FollowRequest reloaded = followRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.PENDING);
    }

    @Test
    void targetCanAcceptPendingRequest() throws Exception { // H
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void otherUserCannotAcceptSomeoneElsesIncomingRequest() throws Exception { // I
        UUID requestId = sendFollowRequestAndGetId();
        registerUser("stranger", "stranger@example.com", ProfileVisibility.PUBLIC);
        String strangerToken = login("stranger");

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void targetCanRejectPendingRequest() throws Exception { // J
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(followRepository.existsByFollowerIdAndFollowingId(requester.getId(), target.getId())).isFalse();
    }

    @Test
    void acceptingRequest_createsFollow() throws Exception { // K
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(requester.getId(), target.getId())).isTrue();
    }

    @Test
    void acceptingRequest_marksRequestAccepted() throws Exception { // L
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        FollowRequest reloaded = followRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.ACCEPTED);
        assertThat(reloaded.getRespondedAt()).isNotNull();
    }

    @Test
    void rejectingRequest_neverCreatesFollow() throws Exception { // M
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        assertThat(followRepository.count()).isZero();
    }

    @Test
    void cancellingRequest_neverCreatesFollow() throws Exception { // N
        UUID requestId = sendFollowRequestAndGetId();

        mockMvc.perform(delete("/api/follow-requests/{requestId}", requestId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNoContent());

        assertThat(followRepository.count()).isZero();
    }

    @Test
    void afterRejection_requesterCanSendANewRequest() throws Exception { // O
        UUID firstRequestId = sendFollowRequestAndGetId();

        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", firstRequestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        UUID secondRequestId = sendFollowRequestAndGetId();

        assertThat(secondRequestId).isNotEqualTo(firstRequestId);
        FollowRequest second = followRequestRepository.findById(secondRequestId).orElseThrow();
        assertThat(second.getStatus()).isEqualTo(FollowRequestStatus.PENDING);
    }

    // ============================================================
    // PRIVACY INTEGRATION (P-Y)
    // ============================================================

    @Test
    void acceptedFollower_seesFullPrivateProfile() throws Exception { // P
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followState").value("FOLLOWING"));
    }

    @Test
    void pendingRequester_doesNotSeeFullProfile() throws Exception { // Q
        sendFollowRequestAndGetId();

        mockMvc.perform(get("/api/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.followState").value("REQUESTED"));
    }

    @Test
    void rejectedRequester_doesNotSeeFullProfile() throws Exception { // R
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.followState").value("NONE"));
    }

    @Test
    void nonFollower_doesNotSeeFullProfile() throws Exception { // S
        mockMvc.perform(get("/api/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.followState").value("NONE"));
    }

    @Test
    void acceptedFollower_seesPublicPost_ofPrivateProfile() throws Exception { // T
        acceptRequest();
        Post post = postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk());
    }

    @Test
    void acceptedFollower_seesFollowersOnlyPost_ofPrivateProfile() throws Exception { // U
        acceptRequest();
        Post post = postRepository.save(Post.builder().author(target).content("seguidores").visibility(PostVisibility.FOLLOWERS_ONLY).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk());
    }

    @Test
    void acceptedFollower_doesNotSeePrivatePost_ofPrivateProfile() throws Exception { // V
        acceptRequest();
        Post post = postRepository.save(Post.builder().author(target).content("privado").visibility(PostVisibility.PRIVATE).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingRequester_doesNotSeeAnyPost() throws Exception { // W
        sendFollowRequestAndGetId();
        Post post = postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingFollower_revokesAccessImmediately() throws Exception { // X
        acceptRequest();
        Post post = postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/follows/followers/{userId}", requester.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void owner_alwaysSeesEverything() throws Exception { // Y
        Post pub = postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());
        Post priv = postRepository.save(Post.builder().author(target).content("privado").visibility(PostVisibility.PRIVATE).build());

        mockMvc.perform(get("/api/posts/{postId}", pub.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/posts/{postId}", priv.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());
    }

    // ============================================================
    // FEED (Z-AC)
    // ============================================================

    @Test
    void feed_includesAllowedContent_fromAcceptedPrivateProfile() throws Exception { // Z
        acceptRequest();
        postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void feed_excludesContent_whilePending() throws Exception { // AA
        sendFollowRequestAndGetId();
        postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feed_excludesContent_afterRejection() throws Exception { // AB
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());
        postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feed_excludesContent_afterCancellation() throws Exception { // AC
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(delete("/api/follow-requests/{requestId}", requestId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNoContent());
        postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feed_disappearsAfterRemoveFollower() throws Exception { // AD
        acceptRequest();
        postRepository.save(Post.builder().author(target).content("publico").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(delete("/api/follows/followers/{userId}", requester.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // ============================================================
    // FOLLOWER MANAGEMENT (AF-AK)
    // ============================================================

    @Test
    void removeFollower_removesCorrectDirection() throws Exception { // AF, AG
        acceptRequest(); // requester -> target (requester sigue a target)

        // El propio requester TAMBIEN sigue... no, invertimos: probamos que
        // remover al requester de los followers de target no afecta si
        // target sigue al requester (relacion inversa independiente).
        followRepository.save(Follow.builder().follower(target).following(requester).build());

        mockMvc.perform(delete("/api/follows/followers/{userId}", requester.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNoContent());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(requester.getId(), target.getId())).isFalse();
        // La relacion inversa (target sigue a requester) sigue intacta.
        assertThat(followRepository.existsByFollowerIdAndFollowingId(target.getId(), requester.getId())).isTrue();
    }

    @Test
    void removeFollower_nonexistent_returnsNotFound() throws Exception { // AH
        mockMvc.perform(delete("/api/follows/followers/{userId}", requester.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void owner_canListIncomingPendingRequests() throws Exception { // AI
        sendFollowRequestAndGetId();

        mockMvc.perform(get("/api/follow-requests/incoming")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].otherUser.username").value("requester"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void requester_canListOutgoingPendingRequests() throws Exception { // AJ
        sendFollowRequestAndGetId();

        mockMvc.perform(get("/api/follow-requests/outgoing")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].otherUser.username").value("target"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void othersDoNotSeeForeignRequests_inIncomingOrOutgoing() throws Exception { // AK
        sendFollowRequestAndGetId();

        User stranger = registerUser("stranger", "stranger@example.com", ProfileVisibility.PUBLIC);
        String strangerToken = login("stranger");

        mockMvc.perform(get("/api/follow-requests/incoming")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/follow-requests/outgoing")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ============================================================
    // NOTIFICATIONS (AL-AN)
    // ============================================================

    @Test
    void requestGeneratesNotification_toTarget() throws Exception { // AL
        sendFollowRequestAndGetId();

        long count = notificationRepository.findByRecipientId(target.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(n -> n.getType() == NotificationType.FOLLOW_REQUEST_RECEIVED)
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void acceptGeneratesNotification_toRequester() throws Exception { // AM
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        long count = notificationRepository.findByRecipientId(requester.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(n -> n.getType() == NotificationType.FOLLOW_REQUEST_ACCEPTED)
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void retryingDuplicateRequest_doesNotDuplicateNotification() throws Exception { // AN
        sendFollowRequestAndGetId();
        sendFollowRequestAndGetId();
        sendFollowRequestAndGetId();

        long count = notificationRepository.findByRecipientId(target.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(n -> n.getType() == NotificationType.FOLLOW_REQUEST_RECEIVED)
                .count();
        assertThat(count).isEqualTo(1);
    }

    // ============================================================
    // ERRORES / ESTADOS TERMINALES
    // ============================================================

    @Test
    void acceptingAlreadyAcceptedRequest_fails() throws Exception {
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingAlreadyRejectedRequest_fails() throws Exception {
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/follow-requests/{requestId}", requestId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptingNonexistentRequest_returnsNotFound() throws Exception {
        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", UUID.randomUUID())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // CONCURRENCIA
    // ============================================================

    @Test
    void onlyOneOfTwoConcurrentAccepts_succeeds() throws Exception { // claim atomico
        UUID requestId = sendFollowRequestAndGetId();

        // Transaccion explicita por hilo: cada @Modifying query necesita su
        // propia transaccion activa para ejecutarse (mismo fix que
        // AuthSessionIntegrationTest.shouldOnlyAllowOneWinner... en Fase 1.5).
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claim = () -> transactionTemplate.execute(
                    status -> followRequestRepository.claimAccept(requestId));

            Future<Integer> first = executor.submit(claim);
            Future<Integer> second = executor.submit(claim);

            int totalClaimed = first.get() + second.get();
            assertThat(totalClaimed).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    private void acceptRequest() throws Exception {
        UUID requestId = sendFollowRequestAndGetId();
        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());
    }
}
