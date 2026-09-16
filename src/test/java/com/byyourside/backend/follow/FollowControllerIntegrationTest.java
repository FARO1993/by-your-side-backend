package com.byyourside.backend.follow;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FollowControllerIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User facu;
    private User soumia;
    private String facuToken;

    @BeforeEach
    void setUp() throws Exception {
        followRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        soumia = registerUser("soumia", "soumia@example.com");
        facuToken = login("facu");
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
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    private record LoginPayload(String username, String password) {
    }

    @Test
    void shouldFollowUser_whenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followerId").value(facu.getId().toString()))
                .andExpect(jsonPath("$.followingId").value(soumia.getId().toString()));
    }

    @Test
    void shouldReturnConflict_whenAlreadyFollowing() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        mockMvc.perform(post("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already following this user"));
    }

    @Test
    void shouldReturnBadRequest_whenFollowingSelf() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot follow yourself"));
    }

    @Test
    void shouldReturnNotFound_whenFollowingNonexistentUser() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnUnauthorized_whenFollowingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", soumia.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldUnfollowUser_whenCurrentlyFollowing() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        mockMvc.perform(delete("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        assert !followRepository.existsByFollowerIdAndFollowingId(facu.getId(), soumia.getId());
    }

    @Test
    void shouldReturnNotFound_whenUnfollowingUserNotFollowed() throws Exception {
        mockMvc.perform(delete("/api/follows/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnFollowers_forGivenUser() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        mockMvc.perform(get("/api/follows/{userId}/followers", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("facu"));
    }

    @Test
    void shouldReturnFollowing_forGivenUser() throws Exception {
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());

        mockMvc.perform(get("/api/follows/{userId}/following", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("soumia"));
    }

    @Test
    void shouldReturnEmptyList_whenUserHasNoFollowers() throws Exception {
        mockMvc.perform(get("/api/follows/{userId}/followers", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}