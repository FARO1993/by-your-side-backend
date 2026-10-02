package com.byyourside.backend.status;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.user.ProfileVisibility;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StatusControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private StatusReactionRepository statusReactionRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private NotificationRepository notificationRepository;

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
        notificationRepository.deleteAll();
        statusReactionRepository.deleteAll();
        statusRepository.deleteAll();
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
    void shouldSetStatus_whenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/statuses")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mood": "DIFFICULT_DAY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mood").value("DIFFICULT_DAY"))
                .andExpect(jsonPath("$.reactionCount").value(0));
    }

    @Test
    void shouldIncludeOwnAndFollowedStatuses_inFeed() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        statusRepository.save(Status.builder()
                .user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        statusRepository.save(Status.builder()
                .user(soumia).mood(StatusMood.NEED_TO_TALK)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldExcludeExpiredStatus_fromFeed() throws Exception {
        statusRepository.save(Status.builder()
                .user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)) // ya vencido
                .build());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeNonFollowedUsersStatus_fromFeed() throws Exception {
        statusRepository.save(Status.builder()
                .user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/statuses/feed")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldReactToStatus_andCreateNotification() throws Exception {
        Status status = statusRepository.save(Status.builder()
                .user(facu).mood(StatusMood.NEED_TO_TALK)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactionCount").value(1))
                .andExpect(jsonPath("$.reactedByCurrentUser").value("WITH_YOU"));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.content[0].type").value("NEW_STATUS_REACTION"));
    }

    @Test
    void shouldUpdateReactionType_whenReactingTwiceWithDifferentType() throws Exception {
        Status status = statusRepository.save(Status.builder()
                .user(facu).mood(StatusMood.NEED_TO_TALK)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "NOT_ALONE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactionCount").value(1)) // sigue siendo 1, no 2
                .andExpect(jsonPath("$.reactedByCurrentUser").value("NOT_ALONE"));
    }

    @Test
    void shouldRemoveReaction() throws Exception {
        Status status = statusRepository.save(Status.builder()
                .user(facu).mood(StatusMood.NEED_TO_TALK)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/statuses/{id}/react", status.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactionCount").value(0))
                .andExpect(jsonPath("$.reactedByCurrentUser").isEmpty());
    }

    // --- historial de animo propio (GET /api/statuses/mine/history) ---

    private Status saveStatus(User user, StatusMood mood, int daysAgo) {
        Status status = statusRepository.save(Status.builder()
                .user(user).mood(mood)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        // created_at es updatable=false en JPA y lo pone @PrePersist: para
        // simular dias anteriores se ajusta directo en la base.
        Instant createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        jdbcTemplate.update("UPDATE statuses SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(createdAt), Timestamp.from(createdAt.plus(24, ChronoUnit.HOURS)), status.getId());
        return status;
    }

    @Test
    void shouldReturnOwnMoodHistory_includingExpired_newestFirst() throws Exception {
        saveStatus(facu, StatusMood.DIFFICULT_DAY, 5);
        saveStatus(facu, StatusMood.WELL, 2);
        saveStatus(facu, StatusMood.NEED_DISTRACTION, 0);

        mockMvc.perform(get("/api/statuses/mine/history")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].mood").value("NEED_DISTRACTION"))
                .andExpect(jsonPath("$[1].mood").value("WELL"))
                .andExpect(jsonPath("$[2].mood").value("DIFFICULT_DAY"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                // Vista privada: sin datos de usuario ni reacciones.
                .andExpect(jsonPath("$[0].user").doesNotExist())
                .andExpect(jsonPath("$[0].reactionCount").doesNotExist());
    }

    @Test
    void shouldOnlyIncludeTheRequestedDays() throws Exception {
        saveStatus(facu, StatusMood.WELL, 40);
        saveStatus(facu, StatusMood.DIFFICULT_DAY, 10);
        saveStatus(facu, StatusMood.NEED_TO_TALK, 1);

        mockMvc.perform(get("/api/statuses/mine/history")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/statuses/mine/history").param("days", "7")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mood").value("NEED_TO_TALK"));

        mockMvc.perform(get("/api/statuses/mine/history").param("days", "90")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void shouldNeverIncludeOtherPeoplesMoods_evenIfFollowed() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        saveStatus(soumia, StatusMood.DIFFICULT_DAY, 1);
        saveStatus(facu, StatusMood.WELL, 1);

        mockMvc.perform(get("/api/statuses/mine/history")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mood").value("WELL"));
    }

    @Test
    void shouldRejectOutOfRangeDays() throws Exception {
        mockMvc.perform(get("/api/statuses/mine/history").param("days", "0")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/statuses/mine/history").param("days", "91")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnUnauthorized_forMoodHistoryWithoutToken() throws Exception {
        mockMvc.perform(get("/api/statuses/mine/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(get("/api/statuses/feed"))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // STATUS DIRECTO POR USUARIO (Backend Debt B2)
    // ============================================================

    @Test
    void directStatus_ownerWithStatus_returnsIt() throws Exception { // X
        statusRepository.save(Status.builder().user(facu).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("WELL"));
    }

    @Test
    void directStatus_otherUserWithVisibleStatus_returnsIt() throws Exception { // Y
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.NEED_TO_TALK)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("NEED_TO_TALK"))
                .andExpect(jsonPath("$.user.username").value("soumia"));
    }

    @Test
    void directStatus_userWithoutStatus_returnsNullBody() throws Exception { // Z
        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void directStatus_expiredStatus_doesNotAppear() throws Exception { // AA
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void directStatus_returnsMostRecentActive() throws Exception { // AB
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());
        Thread.sleep(10);
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.HERE_FOR_SOMEONE)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("HERE_FOR_SOMEONE"));
    }

    @Test
    void directStatus_privateProfile_acceptedFollower_isVisible() throws Exception { // AC
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(facu).following(privateUser).build());
        statusRepository.save(Status.builder().user(privateUser).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("WELL"));
    }

    @Test
    void directStatus_privateProfile_pendingRequest_notVisible() throws Exception { // AD
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        followRequestRepository.save(FollowRequest.builder().requester(facu).target(privateUser).build());
        statusRepository.save(Status.builder().user(privateUser).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void directStatus_privateProfile_none_notVisible() throws Exception { // AE
        User privateUser = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        statusRepository.save(Status.builder().user(privateUser).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/status", privateUser.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void directStatus_blockedByTarget_returnsNotFound() throws Exception { // AF
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/users/{userId}/block", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void directStatus_blockerSide_alsoReturnsNotFound() throws Exception { // AF (sentido inverso)
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void directStatus_muteDoesNotBlockAccess() throws Exception { // AG
        statusRepository.save(Status.builder().user(soumia).mood(StatusMood.WELL)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/users/{userId}/mute", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}/status", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("WELL"));
    }

    @Test
    void directStatus_nonexistentUser_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/users/{userId}/status", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }
}