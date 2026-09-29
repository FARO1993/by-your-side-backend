package com.byyourside.backend.notification;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
import com.byyourside.backend.postresponse.PostResponseRepository;
import com.byyourside.backend.status.Status;
import com.byyourside.backend.status.StatusMood;
import com.byyourside.backend.status.StatusReactionRepository;
import com.byyourside.backend.status.StatusRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class NotificationControllerIntegrationTest {

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
    private PostResponseRepository postResponseRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private StatusReactionRepository statusReactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
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
        return registerUser(username, email, ProfileVisibility.PUBLIC);
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
        String body = objectMapper.writeValueAsString(new LoginPayload(username + "@example.com", "secretpass123"));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private record LoginPayload(String email, String password) {
    }

    @Test
    void shouldCreateNotification_whenSomeoneFollowsYou() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_FOLLOWER"))
                .andExpect(jsonPath("$.content[0].actor.username").value("soumia"))
                .andExpect(jsonPath("$.content[0].read").value(false));
    }

    @Test
    void shouldCreateNotification_whenSomeoneCommentsYourPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "que fuerte tu post"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_COMMENT"))
                .andExpect(jsonPath("$.content[0].postId").value(post.getId().toString()));
    }

    @Test
    void shouldNotCreateNotification_whenCommentingYourOwnPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentando mi propio post"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void shouldCreateNotification_whenSomeoneRespondsToYourPost() throws Exception { // Backend Debt B1: NEW_SUPPORT -> NEW_POST_RESPONSE
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_POST_RESPONSE"));
    }

    @Test
    void shouldReturnUnreadCount() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void shouldMarkAllAsRead() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void shouldReturnUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // MARK ONE (Backend Debt B3)
    // ============================================================

    @Test
    void markAsRead_ownerMarksUnreadNotification_readBecomesTrue() throws Exception { // A/B
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        UUID notificationId = firstNotificationId(facuToken);

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void markAsRead_decrementsUnreadCount() throws Exception { // C
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        UUID notificationId = firstNotificationId(facuToken);

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void markAsRead_secondCall_isIdempotent() throws Exception { // D
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        UUID notificationId = firstNotificationId(facuToken);

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void markAsRead_doesNotAffectOtherNotifications() throws Exception { // E
        Post post = postRepository.save(Post.builder()
                .author(facu).content("post").visibility(PostVisibility.PUBLIC).build());
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentario"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        List<UUID> ids = notificationIds(facuToken);
        assertThat(ids).hasSize(2);

        mockMvc.perform(patch("/api/notifications/{id}/read", ids.get(0))
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markAsRead_anotherUsersNotification_returnsNotFound() throws Exception { // F
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        UUID notificationId = firstNotificationId(facuToken);

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNotFound());

        // La notificacion de facu sigue sin leer -- soumia no logro nada.
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markAsRead_nonexistentNotification_returnsNotFound() throws Exception { // G
        mockMvc.perform(patch("/api/notifications/{id}/read", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // READ ALL — REGRESION (Backend Debt B3)
    // ============================================================

    @Test
    void readAll_marksAllOwnNotifications() throws Exception { // H
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        Post post = postRepository.save(Post.builder()
                .author(facu).content("post").visibility(PostVisibility.PUBLIC).build());
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentario"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.content[0].read").value(true))
                .andExpect(jsonPath("$.content[1].read").value(true));
    }

    @Test
    void readAll_doesNotMarkOtherUsersNotifications() throws Exception { // I
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        // La notificacion de soumia (facu la siguio a ella) sigue sin leer.
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markOneThenReadAll_workTogether() throws Exception { // K
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());
        Post post = postRepository.save(Post.builder()
                .author(facu).content("post").visibility(PostVisibility.PUBLIC).build());
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentario"}
                                """))
                .andExpect(status().isCreated());

        UUID first = notificationIds(facuToken).get(0);
        mockMvc.perform(patch("/api/notifications/{id}/read", first)
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.count").value(0));
    }

    // ============================================================
    // STATUS ID (Backend Debt B3)
    // ============================================================

    @Test
    void newStatusReaction_containsCorrectStatusId() throws Exception { // L/N
        Status status = statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_STATUS_REACTION"))
                .andExpect(jsonPath("$.content[0].statusId").value(status.getId().toString()));
    }

    @Test
    void newStatusReaction_doesNotUsePostIdField() throws Exception { // M
        Status status = statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.content[0].postId").doesNotExist());
    }

    @Test
    void statusReaction_onBlockedPair_doesNotCreateNotification() throws Exception { // P
        Status status = statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isNotFound());

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void historicalNotification_withStatusIdOfExpiredStatus_stillReadsFine() throws Exception { // Q
        // Status nunca se borra (solo expira) -- simula una notificacion
        // historica cuyo status ya vencio, para confirmar que la
        // deserializacion no se rompe (statusId es una columna sin FK, ver
        // V13).
        Status expired = statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        Notification notification = Notification.builder()
                .recipient(facu).actor(soumia).type(NotificationType.NEW_STATUS_REACTION)
                .statusId(expired.getId()).build();
        notificationRepository.save(notification);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].statusId").value(expired.getId().toString()));
    }

    // ============================================================
    // FOLLOW REQUEST ID (Backend Debt B3)
    // ============================================================

    @Test
    void followRequestReceived_containsFollowRequestId_pointingToPendingRequest() throws Exception { // R/S
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        String privateToken = login("privado");

        mockMvc.perform(post("/api/follows/{userId}", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        String response = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("FOLLOW_REQUEST_RECEIVED"))
                .andReturn().getResponse().getContentAsString();

        UUID followRequestId = UUID.fromString(
                objectMapper.readTree(response).get("content").get(0).get("followRequestId").asText());

        FollowRequest pending = followRequestRepository.findById(followRequestId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(FollowRequestStatus.PENDING);
        assertThat(pending.getRequester().getId()).isEqualTo(facu.getId());
        assertThat(pending.getTarget().getId()).isEqualTo(privateUser.getId());
    }

    @Test
    void followRequestId_fromNotification_canBeUsedToAccept() throws Exception { // T
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        String privateToken = login("privado");

        mockMvc.perform(post("/api/follows/{userId}", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        UUID followRequestId = followRequestIdFromLatestNotification(privateToken);

        mockMvc.perform(post("/api/follow-requests/{id}/accept", followRequestId)
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), privateUser.getId())).isTrue();
    }

    @Test
    void followRequestId_fromNotification_canBeUsedToReject() throws Exception { // U
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        String privateToken = login("privado");

        mockMvc.perform(post("/api/follows/{userId}", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        UUID followRequestId = followRequestIdFromLatestNotification(privateToken);

        mockMvc.perform(post("/api/follow-requests/{id}/reject", followRequestId)
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(followRepository.existsByFollowerIdAndFollowingId(facu.getId(), privateUser.getId())).isFalse();
    }

    @Test
    void followRequestId_pointingToTerminalRequest_stillHandledByFollowRequestService() throws Exception { // W
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        String privateToken = login("privado");

        mockMvc.perform(post("/api/follows/{userId}", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());

        UUID followRequestId = followRequestIdFromLatestNotification(privateToken);

        mockMvc.perform(post("/api/follow-requests/{id}/accept", followRequestId)
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isOk());

        // El mismo followRequestId, ahora ACCEPTED (terminal) -- intentar
        // aceptar de nuevo lo maneja FollowRequestService con su 409 normal,
        // sin ningun estado duplicado en Notification.
        mockMvc.perform(post("/api/follow-requests/{id}/accept", followRequestId)
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isConflict());
    }

    @Test
    void followRequestAccepted_stillWorks_andIncludesFollowRequestId() throws Exception { // X
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        String privateToken = login("privado");

        mockMvc.perform(post("/api/follows/{userId}", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated());
        UUID followRequestId = followRequestIdFromLatestNotification(privateToken);

        mockMvc.perform(post("/api/follow-requests/{id}/accept", followRequestId)
                        .header("Authorization", "Bearer " + privateToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("FOLLOW_REQUEST_ACCEPTED"))
                .andExpect(jsonPath("$.content[0].followRequestId").value(followRequestId.toString()));
    }

    // ============================================================
    // PAGINACION (Backend Debt B3)
    // ============================================================

    @Test
    void pagination_page0_hasStableOrder_mostRecentFirst() throws Exception { // Y
        createNotifications(soumia, facu, 3);

        List<UUID> firstCall = notificationIds(facuToken);
        List<UUID> secondCall = notificationIds(facuToken);
        assertThat(firstCall).isEqualTo(secondCall);
    }

    @Test
    void pagination_page1_continuesCorrectly_noDuplicates() throws Exception { // Z/AA
        createNotifications(soumia, facu, 5);

        String page0 = mockMvc.perform(get("/api/notifications").param("page", "0").param("size", "2")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String page1 = mockMvc.perform(get("/api/notifications").param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> page0Ids = idsFromPageJson(page0);
        List<String> page1Ids = idsFromPageJson(page1);

        assertThat(page0Ids).hasSize(2);
        assertThat(page1Ids).hasSize(2);
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    void pagination_tiedCreatedAt_resolvedByIdDesc() throws Exception { // AB
        User third = registerUser("otro", "otro@example.com");
        Notification n1 = notificationRepository.save(Notification.builder()
                .recipient(facu).actor(soumia).type(NotificationType.NEW_FOLLOWER).build());
        Notification n2 = notificationRepository.save(Notification.builder()
                .recipient(facu).actor(third).type(NotificationType.NEW_FOLLOWER).build());

        // Fix de flaky: java.util.UUID.compareTo() NO equivale al ORDER BY
        // ... DESC que Postgres aplica sobre una columna `uuid` -- Java
        // compara mostSigBits/leastSigBits como long CON SIGNO, mientras
        // Postgres compara los 16 bytes del UUID SIN signo. Con
        // UUID.randomUUID() (lo que generaba el id real antes de este fix),
        // ambos ordenes discrepan cada vez que el primer byte que difiere
        // tiene el bit alto seteado en uno de los dos UUID -- ~50% de las
        // corridas, el test fallaba de forma no determinista.
        //
        // Solucion: reasignar el id de las dos filas via JDBC directo
        // (mismo patron ya usado abajo para createdAt, que tambien evita la
        // gestion de @GeneratedValue de Hibernate) a un par de UUID
        // CONSTRUIDOS a mano que comparten mostSigBits y solo difieren en
        // el byte MENOS significativo de leastSigBits. En ese caso puntual
        // -- un unico byte de diferencia, sin cruzar el bit de signo de
        // ningun long -- comparar como long con signo (Java) y comparar
        // bytes sin signo (Postgres) dan EXACTAMENTE el mismo resultado:
        // el orden esperado deja de depender de que UUID.randomUUID()
        // "tenga suerte", sin reimplementar el algoritmo de comparacion de
        // Postgres ni consultar el orden esperado a la DB (evita un test
        // tautologico).
        UUID base = UUID.randomUUID();
        long fixedMostSigBits = base.getMostSignificantBits();
        long leastSigBitsPrefix = base.getLeastSignificantBits() & ~0xFFL;
        UUID smallerId = new UUID(fixedMostSigBits, leastSigBitsPrefix | 0x01L);
        UUID largerId = new UUID(fixedMostSigBits, leastSigBitsPrefix | 0x02L);

        // @PrePersist siempre pisa createdAt con Instant.now() al guardar --
        // para forzar un empate REAL (no solo "muy cercano"), se iguala el
        // timestamp via JDBC directo despues del insert (evita tanto
        // @Column(updatable = false) de la entidad como la necesidad de una
        // transaccion JPA activa en el test).
        java.sql.Timestamp ts = java.sql.Timestamp.from(Instant.now());
        jdbcTemplate.update("UPDATE notifications SET id = ?, created_at = ? WHERE id = ?",
                smallerId, ts, n1.getId());
        jdbcTemplate.update("UPDATE notifications SET id = ?, created_at = ? WHERE id = ?",
                largerId, ts, n2.getId());

        List<UUID> firstCall = notificationIds(facuToken);
        List<UUID> secondCall = notificationIds(facuToken);
        assertThat(firstCall).isEqualTo(secondCall);
        assertThat(firstCall).containsExactlyInAnyOrder(smallerId, largerId);
        // Con createdAt empatado, el desempate por id DESC define un orden
        // determinista -- largerId siempre primero, tanto para Postgres
        // (bytes sin signo) como para la construccion de arriba (unico
        // byte que difiere, sin cruzar el bit de signo) -- misma order en
        // ambas llamadas, sin depender del azar de UUID.randomUUID().
        assertThat(firstCall.get(0)).isEqualTo(largerId);
    }

    @Test
    void pagination_sizeParam_isRespected() throws Exception { // AC
        createNotifications(soumia, facu, 5);

        mockMvc.perform(get("/api/notifications").param("size", "3")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void pagination_metadata_isCorrect() throws Exception { // AD
        createNotifications(soumia, facu, 5);

        mockMvc.perform(get("/api/notifications").param("page", "0").param("size", "2")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void pagination_onlyReturnsOwnNotifications() throws Exception { // AE
        // 2 notificaciones para facu (soumia como actor) + 1 para soumia
        // (facu como actor) -- cada uno debe ver solo las suyas.
        createNotifications(soumia, facu, 2);
        createNotifications(facu, soumia, 1);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].actor.username").value("soumia"))
                .andExpect(jsonPath("$.content[1].actor.username").value("soumia"));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].actor.username").value("facu"));
    }

    private void createNotifications(User actor, User recipient, int count) {
        for (int i = 0; i < count; i++) {
            notificationRepository.save(Notification.builder()
                    .recipient(recipient).actor(actor).type(NotificationType.NEW_FOLLOWER).build());
        }
    }

    private List<UUID> notificationIds(String token) throws Exception {
        String response = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return idsFromPageJson(response).stream().map(UUID::fromString).toList();
    }

    // Solo el "id" de cada notificacion en $.content[*] -- NO
    // content.findValues("id"), que recorre TODO el arbol JSON y tambien
    // trae actor.id (bug detectado corriendo este mismo test).
    private List<String> idsFromPageJson(String pageJson) throws Exception {
        JsonNode content = objectMapper.readTree(pageJson).get("content");
        List<String> ids = new java.util.ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private UUID firstNotificationId(String token) throws Exception {
        return notificationIds(token).get(0);
    }

    private UUID followRequestIdFromLatestNotification(String token) throws Exception {
        String response = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(
                objectMapper.readTree(response).get("content").get(0).get("followRequestId").asText());
    }
}