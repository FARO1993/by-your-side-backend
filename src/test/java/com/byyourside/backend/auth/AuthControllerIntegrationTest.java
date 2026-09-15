package com.byyourside.backend.auth;

import com.byyourside.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void shouldRegisterNewUserSuccessfully() throws Exception {
        String body = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("facu"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void shouldReturnConflict_whenUsernameAlreadyExists() throws Exception {
        String firstUser = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstUser))
                .andExpect(status().isCreated());

        String duplicateUsername = """
                {
                    "username": "facu",
                    "email": "otro@example.com",
                    "password": "otherpass123",
                    "displayName": "Otro"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateUsername))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already taken"));
    }

    @Test
    void shouldReturnBadRequest_whenPasswordIsTooShort() throws Exception {
        String body = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "123",
                    "displayName": "Facu"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void shouldLoginSuccessfully_whenCredentialsAreValid() throws Exception {
        String registerBody = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {
                    "username": "facu",
                    "password": "secretpass123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("facu"));
    }

    @Test
    void shouldReturnUnauthorized_whenPasswordIsWrong() throws Exception {
        String registerBody = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String wrongLoginBody = """
                {
                    "username": "facu",
                    "password": "wrongpassword"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongLoginBody))
                .andExpect(status().isUnauthorized());
    }
}