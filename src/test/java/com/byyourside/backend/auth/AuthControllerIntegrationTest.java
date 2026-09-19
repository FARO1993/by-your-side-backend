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
                    "displayName": "Facu",
                    "email": "facu@example.com",
                    "password": "secretpass123"
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
    void shouldGenerateDistinctUsername_whenDisplayNameCollides() throws Exception {
        String first = """
                {"displayName": "Facu", "email": "facu1@example.com", "password": "secretpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("facu"));

        String second = """
                {"displayName": "Facu", "email": "facu2@example.com", "password": "secretpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("facu1"));
    }

    @Test
    void shouldReturnConflict_whenEmailAlreadyExists() throws Exception {
        String body = """
                {"displayName": "Facu", "email": "facu@example.com", "password": "secretpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String duplicateEmail = """
                {"displayName": "Otro", "email": "facu@example.com", "password": "otherpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void shouldReturnBadRequest_whenPasswordIsTooShort() throws Exception {
        String body = """
                {"displayName": "Facu", "email": "facu@example.com", "password": "123"}
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
                {"displayName": "Facu", "email": "facu@example.com", "password": "secretpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email": "facu@example.com", "password": "secretpass123"}
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
                {"displayName": "Facu", "email": "facu@example.com", "password": "secretpass123"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String wrongLoginBody = """
                {"email": "facu@example.com", "password": "wrongpassword"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongLoginBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorized_whenEmailDoesNotExist() throws Exception {
        String body = """
                {"email": "noexiste@example.com", "password": "cualquierpass"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}