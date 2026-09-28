package com.byyourside.backend.block;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.availability.Availability;
import com.byyourside.backend.availability.AvailabilityRepository;
import com.byyourside.backend.availability.CompanionIntent;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
import com.byyourside.backend.report.ReportRepository;
import com.byyourside.backend.status.Status;
import com.byyourside.backend.status.StatusMood;
import com.byyourside.backend.status.StatusReactionRepository;
import com.byyourside.backend.status.StatusReactionType;
import com.byyourside.backend.status.StatusRepository;
import com.byyourside.backend.postresponse.PostResponseRepository;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import com.byyourside.backend.user.UserStatus;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Fase 9.4: bloqueo de usuario a usuario.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BlockIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserMuteRepository userMuteRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private PostResponseRepository postResponseRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private StatusReactionRepository statusReactionRepository;

    @Autowired
    private AvailabilityRepository availabilityRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        reportRepository.deleteAll();
        notificationRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        availabilityRepository.deleteAll();
        statusReactionRepository.deleteAll();
        statusRepository.deleteAll();
        commentRepository.deleteAll();
        postResponseRepository.deleteAll();
        postRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email) {
        return registerUser(username, email, UserRole.USER);
    }

    private User registerUser(String username, String email, UserRole role) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(role)
                .status(UserStatus.ACTIVE)
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

    private void block(String blockerToken, UUID targetId) throws Exception {
        mockMvc.perform(post("/api/users/{userId}/block", targetId)
                        .header("Authorization", "Bearer " + blockerToken))
                .andExpect(status().isNoContent());
    }

    // ============================================================
    // CORE (A-I)
    // ============================================================

    @Test
    void blockingUser_createsBlockRow() throws Exception { // A
        block(facuToken, soumia.getId());

        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(facu.getId(), soumia.getId())).isTrue();
    }

    @Test
    void blockingSelf_fails() throws Exception { // B
        mockMvc.perform(post("/api/users/{userId}/block", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot block yourself"));
    }

    @Test
    void blockingNonexistentUser_returnsNotFound() throws Exception { // C
        mockMvc.perform(post("/api/users/{userId}/block", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void blockingAlreadyBlockedUser_isIdempotent() throws Exception { // D
        block(facuToken, soumia.getId());
        block(facuToken, soumia.getId());

        assertThat(userBlockRepository.count()).isEqualTo(1);
    }

    @Test
    void unblockingUserNeverBlocked_isIdempotentNoop() throws Exception { // E
        mockMvc.perform(delete("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(userBlockRepository.count()).isZero();
    }

    @Test
    void unblockingUser_removesBlockRow() throws Exception { // F
        block(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void unblocking_doesNotRecreateFollow() throws Exception { // G
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        block(facuToken, soumia.getId()); // limpia el Follow

        mockMvc.perform(delete("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void unblocking_doesNotReactivateFollowRequest() throws Exception { // H
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(facu).target(soumia).build());
        block(soumiaToken, facu.getId()); // soumia bloquea a facu -> cancela el pending

        mockMvc.perform(delete("/api/users/{userId}/block", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNoContent());

        FollowRequest reloaded = followRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
    }

    @Test
    void onlyBlockerIdentityFromPrincipal_neverFromBody() throws Exception { // I
        // El body no tiene forma de mandar un blockerId distinto -- el path
        // es siempre /{userId}/block y el blocker es siempre el principal.
        // Este test confirma que bloquear "a" alguien nunca crea una fila en
        // sentido inverso.
        block(facuToken, soumia.getId());

        assertThat(userBlockRepository.existsByBlockerIdAndBlockedId(soumia.getId(), facu.getId())).isFalse();
    }

    // ============================================================
    // FOLLOW CLEANUP (J-R)
    // ============================================================

    @Test
    void blocking_deletesExistingFollow_blockerFollowsTarget() throws Exception { // J
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        block(facuToken, soumia.getId());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void blocking_deletesExistingFollow_targetFollowsBlocker() throws Exception { // K
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());

        block(facuToken, soumia.getId());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(soumia.getId(), facu.getId())).isFalse();
    }

    @Test
    void blocking_deletesBothFollowRows_ifMutualFollow() throws Exception { // L
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());

        block(facuToken, soumia.getId());

        assertThat(followRepository.count()).isZero();
    }

    @Test
    void blocking_cancelsPendingFollowRequest_fromBlockerToTarget() throws Exception { // M
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(facu).target(soumia).build());

        block(facuToken, soumia.getId());

        FollowRequest reloaded = followRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
    }

    @Test
    void blocking_cancelsPendingFollowRequest_fromTargetToBlocker() throws Exception { // N
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(soumia).target(facu).build());

        block(facuToken, soumia.getId());

        FollowRequest reloaded = followRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
    }

    @Test
    void blocking_doesNotTouchHistoricalAcceptedFollowRequest() throws Exception { // O
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(facu).target(soumia).status(FollowRequestStatus.ACCEPTED).build());
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        block(facuToken, soumia.getId());

        FollowRequest reloaded = followRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.ACCEPTED); // sin tocar
        assertThat(followRepository.count()).isZero(); // el Follow real si se borro
    }

    @Test
    void afterBlock_cannotCreateNewFollow() throws Exception { // P
        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void afterBlock_reverseSideAlsoCannotFollow() throws Exception { // Q
        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void afterBlock_oldPendingRequestCannotBeAccepted() throws Exception { // R
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(facu).target(soumia).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/follow-requests/{requestId}/accept", request.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isConflict());
    }

    // ============================================================
    // PROFILE / POSTS (S-Z)
    // ============================================================

    @Test
    void profileView_blockedByTarget_returnsNotFound() throws Exception { // S
        block(soumiaToken, facu.getId()); // soumia bloqueo a facu

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void profileView_iBlockedThem_stillReturnsOkWithFlag() throws Exception { // T
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedByCurrentUser").value(true));
    }

    @Test
    void profileView_iBlockedThem_bioHidden() throws Exception { // U
        soumia.setBio("mi bio");
        userRepository.save(soumia);
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist());
    }

    @Test
    void profileView_noBlock_blockedByCurrentUserIsFalse() throws Exception { // regresion
        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedByCurrentUser").value(false));
    }

    @Test
    void getPost_blockedByAuthor_returnsNotFound() throws Exception { // V
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPost_iBlockedTheAuthor_returnsNotFound() throws Exception { // W
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserPosts_blockedEitherDirection_returnsEmptyPage() throws Exception { // X
        postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/{userId}/posts", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void commentingOnBlockedUsersPost_returnsNotFound() throws Exception { // Y
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentario"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void supportingBlockedUsersPost_returnsNotFound() throws Exception { // Z
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void owner_stillSeesOwnPosts_afterBlockingSomeoneElse() throws Exception { // regresion
        Post post = postRepository.save(Post.builder().author(facu).content("mio").visibility(PostVisibility.PRIVATE).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    // ============================================================
    // FEED / DISCOVER (AA-AD)
    // ============================================================

    @Test
    void feed_excludesBlockedUsersPosts() throws Exception { // AA
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());

        block(facuToken, soumia.getId()); // esto ademas borra el Follow

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void discover_excludesUsersIBlocked() throws Exception { // AB
        block(facuToken, soumia.getId());

        assertThat(discoverIds(facuToken)).doesNotContain(soumia.getId().toString());
    }

    @Test
    void discover_excludesUsersWhoBlockedMe() throws Exception { // AC
        block(soumiaToken, facu.getId());

        assertThat(discoverIds(facuToken)).doesNotContain(soumia.getId().toString());
    }

    @Test
    void discover_stillExcludesFollowedUsers() throws Exception { // AD, regresion 9.x
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        assertThat(discoverIds(facuToken)).doesNotContain(soumia.getId().toString());
    }

    private java.util.List<String> discoverIds(String token) throws Exception {
        String response = mockMvc.perform(get("/api/users/discover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode content = objectMapper.readTree(response).get("content");
        java.util.List<String> ids = new java.util.ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    // ============================================================
    // CHAT (AE-AI)
    // ============================================================

    @Test
    void blocking_preservesExistingConversationHistory() throws Exception { // AE
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        String conversationId = createConversation(facuToken, soumia.getId());
        sendMessage(facuToken, conversationId, "hola antes del bloqueo");

        block(facuToken, soumia.getId());

        assertThat(conversationRepository.findById(UUID.fromString(conversationId))).isPresent();
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void blockedPair_canStillReadOldMessages() throws Exception { // AF
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        String conversationId = createConversation(facuToken, soumia.getId());
        sendMessage(facuToken, conversationId, "hola antes del bloqueo");

        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void blocking_preventsCreatingNewConversation() throws Exception { // AG
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void blocking_preventsNewMessagesInExistingConversation() throws Exception { // AH
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        String conversationId = createConversation(facuToken, soumia.getId());

        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "hola despues del bloqueo"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void companionInitiatedChat_respectsBlock() throws Exception { // AI
        availabilityRepository.save(Availability.builder()
                .user(soumia).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // COMPANION / AVAILABILITY (AJ-AL)
    // ============================================================

    @Test
    void availabilityListing_excludesBlockedUser_blockerSide() throws Exception { // AJ
        availabilityRepository.save(Availability.builder()
                .user(soumia).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void availabilityListing_excludesBlockedUser_blockedSide() throws Exception { // AK
        availabilityRepository.save(Availability.builder()
                .user(facu).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void availability_ownEndpoints_stillWorkRegardlessOfUnrelatedBlocks() throws Exception { // AL, regresion
        block(facuToken, soumia.getId());

        mockMvc.perform(post("/api/availability")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "TALK"}
                                """))
                .andExpect(status().isCreated());
    }

    // ============================================================
    // NOTIFICATIONS (AM-AO)
    // ============================================================

    @Test
    void notificationService_suppressesNotification_whenBlocked() throws Exception { // AM
        block(facuToken, soumia.getId());

        notificationService.notify(soumia, facu, NotificationType.NEW_COMMENT, null);

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void notificationService_stillNotifies_whenNoBlockExists() throws Exception { // AN, regresion
        notificationService.notify(soumia, facu, NotificationType.NEW_COMMENT, null);

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void existingNotifications_notDeleted_afterBlocking() throws Exception { // AO
        notificationService.notify(soumia, facu, NotificationType.NEW_COMMENT, null);
        assertThat(notificationRepository.count()).isEqualTo(1);

        block(facuToken, soumia.getId());

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void statusReaction_onBlockedUsersStatus_returnsNotFound() throws Exception { // interaccion directa fuera de PostAccessPolicy
        Status status = statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        block(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/statuses/{statusId}/react", status.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusFeed_excludesBlockedUsersStatus() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ============================================================
    // BLOCKED LIST (AP-AS)
    // ============================================================

    @Test
    void blockedList_showsOnlyUsersIBlocked_notWhoBlockedMe() throws Exception { // AP
        block(facuToken, soumia.getId());
        User stranger = registerUser("stranger", "stranger@example.com");
        String strangerToken = login("stranger");
        block(strangerToken, facu.getId()); // stranger bloqueo a facu -- no debe aparecer en la lista de facu

        mockMvc.perform(get("/api/users/me/blocked")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(soumia.getId().toString()));
    }

    @Test
    void blockedList_hasMinimalFields_noEmail() throws Exception { // AQ
        block(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/me/blocked")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("soumia"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist());
    }

    @Test
    void blockedList_emptyWhenNoBlocks() throws Exception { // AR
        mockMvc.perform(get("/api/users/me/blocked")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void blockedList_updatesAfterUnblock() throws Exception { // AS
        block(facuToken, soumia.getId());
        mockMvc.perform(delete("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me/blocked")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // ============================================================
    // MODERACION / REPORTES (no deben verse impedidos por un bloqueo)
    // ============================================================

    @Test
    void moderatorCanStillDeletePost_evenIfAuthorBlockedTheModerator() throws Exception {
        User moderator = registerUser("moderator", "moderator@example.com", UserRole.MODERATOR);
        String moderatorToken = login("moderator");
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());

        block(soumiaToken, moderator.getId());

        mockMvc.perform(delete("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + moderatorToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void reportingBlockedUser_stillWorks() throws Exception {
        block(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType": "USER", "targetId": "%s", "reason": "HARASSMENT"}
                                """.formatted(soumia.getId())))
                .andExpect(status().isCreated());
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private String createConversation(String token, UUID otherUserId) throws Exception {
        String response = mockMvc.perform(post("/api/conversations/{userId}", otherUserId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void sendMessage(String token, String conversationId, String content) throws Exception {
        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "%s"}
                                """.formatted(content)))
                .andExpect(status().isCreated());
    }
}
