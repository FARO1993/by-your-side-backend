package com.byyourside.backend.mute;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.companion.CompanionOffering;
import com.byyourside.backend.companion.CompanionOfferingRepository;
import com.byyourside.backend.companion.OfferingType;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.notification.NotificationService;
import com.byyourside.backend.notification.NotificationType;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
import com.byyourside.backend.report.ReportRepository;
import com.byyourside.backend.status.Status;
import com.byyourside.backend.status.StatusMood;
import com.byyourside.backend.status.StatusReactionRepository;
import com.byyourside.backend.status.StatusRepository;
import com.byyourside.backend.postresponse.PostResponseRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Fase 9.5: silenciar (mute) de usuario a usuario. Unilateral, invisible para
// el muted, y a proposito NUNCA control de acceso -- ver MuteService.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MuteIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserMuteRepository userMuteRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

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
    private CompanionOfferingRepository companionOfferingRepository;

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
        companionOfferingRepository.deleteAll();
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

    private void mute(String muterToken, UUID targetId) throws Exception {
        mockMvc.perform(post("/api/users/{userId}/mute", targetId)
                        .header("Authorization", "Bearer " + muterToken))
                .andExpect(status().isNoContent());
    }

    // ============================================================
    // CORE (A-G)
    // ============================================================

    @Test
    void mutingUser_createsMuteRow() throws Exception { // A
        mute(facuToken, soumia.getId());

        assertThat(userMuteRepository.existsByMuterIdAndMutedId(facu.getId(), soumia.getId())).isTrue();
    }

    @Test
    void mute_isUnilateral_doesNotCreateReverseRow() throws Exception { // B
        mute(facuToken, soumia.getId());

        assertThat(userMuteRepository.existsByMuterIdAndMutedId(soumia.getId(), facu.getId())).isFalse();
    }

    @Test
    void mutingSelf_fails() throws Exception { // C
        mockMvc.perform(post("/api/users/{userId}/mute", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot mute yourself"));
    }

    @Test
    void mutingAlreadyMutedUser_isIdempotent() throws Exception { // D
        mute(facuToken, soumia.getId());
        mute(facuToken, soumia.getId());

        assertThat(userMuteRepository.count()).isEqualTo(1);
    }

    @Test
    void unmutingUser_removesMuteRow() throws Exception { // E
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(userMuteRepository.existsByMuterIdAndMutedId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void unmutingUserNeverMuted_isIdempotentNoop() throws Exception { // F
        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(userMuteRepository.count()).isZero();
    }

    @Test
    void mutedUser_profileView_showsNoTraceOfBeingMuted() throws Exception { // G
        mute(facuToken, soumia.getId());

        String response = mockMvc.perform(get("/api/users/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No existe ningun campo "mutingCurrentUser" ni equivalente -- el
        // perfil de quien te muteo se ve identico a cualquier otro.
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.has("mutingCurrentUser")).isFalse();
        assertThat(json.get("mutedByCurrentUser").asBoolean()).isFalse();
    }

    // ============================================================
    // RELACIONES (H-M)
    // ============================================================

    @Test
    void muting_doesNotDeleteExistingFollow_muterFollowsTarget() throws Exception { // H
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        mute(facuToken, soumia.getId());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId())).isTrue();
    }

    @Test
    void muting_doesNotDeleteExistingFollow_targetFollowsMuter() throws Exception { // I
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());

        mute(facuToken, soumia.getId());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(soumia.getId(), facu.getId())).isTrue();
    }

    @Test
    void muting_doesNotCancelPendingFollowRequest() throws Exception { // J
        FollowRequest request = followRequestRepository.save(FollowRequest.builder()
                .requester(facu).target(soumia).build());

        mute(facuToken, soumia.getId());

        FollowRequest reloaded = followRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FollowRequestStatus.PENDING);
    }

    @Test
    void afterMute_followingStillWorks() throws Exception { // K
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId())).isTrue();
    }

    @Test
    void afterMute_followRequestToPrivateProfileStillWorks() throws Exception { // L
        soumia.setProfileVisibility(com.byyourside.backend.user.ProfileVisibility.PRIVATE);
        userRepository.save(soumia);

        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        assertThat(followRequestRepository.existsByRequesterIdAndTargetIdAndStatus(
                facu.getId(), soumia.getId(), FollowRequestStatus.PENDING)).isTrue();
    }

    @Test
    void afterMute_removeFollowerStillWorks() throws Exception { // M
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/follows/followers/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(soumia.getId(), facu.getId())).isFalse();
    }

    // ============================================================
    // ACCESS (N-S) — mute NUNCA es control de acceso
    // ============================================================

    @Test
    void profileView_stillFullyAccessible_afterMute() throws Exception { // N
        soumia.setBio("mi bio");
        userRepository.save(soumia);
        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("mi bio"))
                .andExpect(jsonPath("$.mutedByCurrentUser").value(true));
    }

    @Test
    void postDetail_stillAccessible_afterMute() throws Exception { // O
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    @Test
    void postsByUser_stillAccessible_afterMute() throws Exception { // P
        postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/{userId}/posts", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void commenting_stillWorks_afterMute() throws Exception { // Q
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentario"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void support_stillWorks_afterMute() throws Exception { // R
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());
    }

    @Test
    void statusReaction_stillWorks_afterMute() throws Exception { // R (status)
        Status status = statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/statuses/{statusId}/react", status.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void chat_stillWorks_afterMute() throws Exception { // S
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        mute(facuToken, soumia.getId());

        String response = mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "hola"}
                                """))
                .andExpect(status().isCreated());
    }

    // ============================================================
    // FEED (T-W)
    // ============================================================

    @Test
    void feed_excludesMutedUsersPosts() throws Exception { // T
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());

        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feed_ofMutedUser_isUnaffected() throws Exception { // U
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());
        postRepository.save(Post.builder().author(facu).content("hola de facu").visibility(PostVisibility.PUBLIC).build());

        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void unmute_restoresFeedContent() throws Exception { // V
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void feed_pagination_stillCorrect_withMutedAuthorMixedIn() throws Exception { // W
        User other = registerUser("other", "other@example.com");
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        followRepository.save(Follow.builder().follower(facu).following(other).build());

        for (int i = 0; i < 3; i++) {
            postRepository.save(Post.builder().author(soumia).content("muted-" + i).visibility(PostVisibility.PUBLIC).build());
        }
        for (int i = 0; i < 2; i++) {
            postRepository.save(Post.builder().author(other).content("visible-" + i).visibility(PostVisibility.PUBLIC).build());
        }

        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/posts/feed").param("page", "0").param("size", "20")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ============================================================
    // DISCOVER (Y-AB)
    // ============================================================

    @Test
    void discover_excludesUserIMuted() throws Exception { // Y
        mute(facuToken, soumia.getId());

        assertThat(discoverIds(facuToken)).doesNotContain(soumia.getId().toString());
    }

    @Test
    void discover_ofMutedUser_stillShowsMuter() throws Exception { // Z
        mute(facuToken, soumia.getId());

        assertThat(discoverIds(soumiaToken)).contains(facu.getId().toString());
    }

    @Test
    void unmute_restoresDiscoverAppearance() throws Exception { // AA
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(discoverIds(facuToken)).contains(soumia.getId().toString());
    }

    @Test
    void block_remainsStrongerAndBilateral_regardlessOfMute() throws Exception { // AB
        mute(soumiaToken, facu.getId());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        // Bloqueo bilateral: ninguno de los dos aparece en el discover del otro,
        // sin importar que soumia solo tuviera un mute (mas debil) sobre facu.
        assertThat(discoverIds(facuToken)).doesNotContain(soumia.getId().toString());
        assertThat(discoverIds(soumiaToken)).doesNotContain(facu.getId().toString());
    }

    private List<String> discoverIds(String token) throws Exception {
        String response = mockMvc.perform(get("/api/users/discover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(response).get("content");
        List<String> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    // ============================================================
    // STATUS / PRESENCE (AC-AE)
    // ============================================================

    @Test
    void statusFeed_excludesMutedUsersStatus() throws Exception { // AC
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void statusFeed_ofMutedUser_stillShowsMuter() throws Exception { // AD
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());
        statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unmute_restoresStatusFeedContent() throws Exception { // AE
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ============================================================
    // COMPANION / AVAILABILITY (AF-AI)
    // ============================================================

    @Test
    void availabilityListing_excludesMutedUser() throws Exception { // AF
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void mutedUser_stillSeesMuterInAvailabilityListing() throws Exception { // AG
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mute(soumiaToken, facu.getId()); // soumia (A) mutea a facu (B)

        // facu (B, el muteado) sigue viendo a soumia (A, quien lo muteo) con
        // total normalidad en su propio listado -- mute nunca filtra desde
        // la perspectiva del muted.
        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void existingConversation_stillWorks_regardlessOfAvailabilityMute() throws Exception { // AH
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());

        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        mute(facuToken, soumia.getId());

        // El listado de disponibilidad ya no muestra a soumia para facu...
        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // ...pero la conversacion directa ya existente sigue andando igual.
        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    @Test
    void unmute_restoresAvailabilityEligibility() throws Exception { // AI
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ============================================================
    // NOTIFICATIONS (AJ-AK)
    // ============================================================

    @Test
    void mute_doesNotSuppressNotifications() throws Exception { // AJ
        mute(facuToken, soumia.getId());

        notificationService.notify(facu, soumia, NotificationType.NEW_COMMENT, null);

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void existingNotifications_notTouched_byMuting() throws Exception { // AK
        notificationService.notify(facu, soumia, NotificationType.NEW_COMMENT, null);
        assertThat(notificationRepository.count()).isEqualTo(1);

        mute(facuToken, soumia.getId());

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    // ============================================================
    // MUTE LIST (AL-AO)
    // ============================================================

    @Test
    void mutedList_showsOnlyUsersIMuted_notWhoMutedMe() throws Exception { // AL
        mute(facuToken, soumia.getId());
        User stranger = registerUser("stranger", "stranger@example.com");
        String strangerToken = login("stranger");
        mute(strangerToken, facu.getId()); // stranger muteo a facu -- no debe aparecer en la lista de facu

        mockMvc.perform(get("/api/users/me/muted")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(soumia.getId().toString()));
    }

    @Test
    void mutedList_hasMinimalFields_noEmail() throws Exception { // AM
        mute(facuToken, soumia.getId());

        mockMvc.perform(get("/api/users/me/muted")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("soumia"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist());
    }

    @Test
    void mutedList_updatesAfterUnmute() throws Exception { // AN
        mute(facuToken, soumia.getId());
        mockMvc.perform(delete("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me/muted")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void noEndpointExposesWhoMutedMe() throws Exception { // AO
        mute(soumiaToken, facu.getId());

        // No hay ningun campo en el propio perfil (GET /me no expone nada de
        // mute) ni en el perfil publico que revele quien me muteo.
        String response = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("muted").doesNotContain("Muted").doesNotContain("mutedBy");
    }

    // ============================================================
    // REGRESION BLOCK (AP-AS)
    // ============================================================

    @Test
    void block_stillDeletesFollow_unaffectedByMute() throws Exception { // AP
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void block_stillBilateral_regardlessOfPriorMute() throws Exception { // AQ
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void muting_doesNotBypassExistingBlock() throws Exception { // AR
        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        // Mutear (en cualquier sentido) a alguien ya bloqueado no reabre nada.
        mockMvc.perform(post("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk()); // yo bloquee -> sigo viendo tarjeta limitada

        mockMvc.perform(get("/api/users/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNotFound()); // ella fue bloqueada -> sigue en 404
    }

    @Test
    void blocking_afterMuting_deletesTheMuterOwnMute() throws Exception { // AR (interaccion block+mute)
        mute(facuToken, soumia.getId());
        assertThat(userMuteRepository.existsByMuterIdAndMutedId(facu.getId(), soumia.getId())).isTrue();

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assertThat(userMuteRepository.existsByMuterIdAndMutedId(facu.getId(), soumia.getId())).isFalse();
    }

    @Test
    void blocking_doesNotTouchReverseMute() throws Exception { // AR (interaccion block+mute, sentido inverso)
        mute(soumiaToken, facu.getId()); // soumia muteo a facu

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        // El mute de soumia hacia facu es ajeno a la accion de bloqueo de facu -- se conserva.
        assertThat(userMuteRepository.existsByMuterIdAndMutedId(soumia.getId(), facu.getId())).isTrue();
    }

    @Test
    void profileAndPostAccess_withBlock_remainSecure_regardlessOfMute() throws Exception { // AS
        Post post = postRepository.save(Post.builder().author(soumia).content("hola").visibility(PostVisibility.PUBLIC).build());
        mute(facuToken, soumia.getId());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    // Pequeña ayuda para exponer StatusRepository sin agregar un bean nuevo:
    // se resuelve via el contexto de Spring en un @Autowired de arriba.
    @org.springframework.stereotype.Component
    static class StatusRepositoryHolder {
        final com.byyourside.backend.status.StatusRepository repo;

        StatusRepositoryHolder(com.byyourside.backend.status.StatusRepository repo) {
            this.repo = repo;
        }
    }
}
