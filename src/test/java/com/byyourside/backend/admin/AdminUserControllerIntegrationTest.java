package com.byyourside.backend.admin;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminUserControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User admin;
    private User regularUser;
    private String adminToken;
    private String regularUserToken;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();

        admin = registerUser("admin", "admin@example.com", UserRole.ADMIN);
        regularUser = registerUser("facu", "facu@example.com", UserRole.USER);

        adminToken = login("admin");
        regularUserToken = login("facu");
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
    void shouldPromoteUserToModerator_whenAuthenticatedAsAdmin() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", regularUser.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MODERATOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MODERATOR"));
    }

    @Test
    void shouldReturnForbidden_whenNonAdminTriesToUpdateRole() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", regularUser.getId())
                        .header("Authorization", "Bearer " + regularUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MODERATOR"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnUnauthorized_whenNoTokenProvided() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", regularUser.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MODERATOR"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnNotFound_whenTargetUserDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MODERATOR"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnConflict_whenDemotingTheLastRemainingAdmin() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", admin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "USER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot remove the last remaining admin"));
    }

    @Test
    void shouldAllowDemotingAdmin_whenAnotherAdminExists() throws Exception {
        User secondAdmin = registerUser("otroadmin", "otroadmin@example.com", UserRole.ADMIN);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", secondAdmin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "USER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }
}