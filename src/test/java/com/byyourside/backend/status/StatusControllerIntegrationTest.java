package com.byyourside.backend.status;

import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.notification.NotificationRepository;
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
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private StatusReactionRepository statusReactionRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private NotificationRepository notificationRepository;

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
        followRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload(username, "secretpass123"));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private record LoginPayload(String username, String password) {
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

    @Test
    void shouldReturnUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(get("/api/statuses/feed"))
                .andExpect(status().isUnauthorized());
    }
}