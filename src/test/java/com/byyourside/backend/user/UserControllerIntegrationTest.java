package com.byyourside.backend.user;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class UserControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();

        String registerBody = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        token = objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void shouldReturnCurrentUser_whenTokenIsValid() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("facu"))
                .andExpect(jsonPath("$.email").value("facu@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void shouldReturnUnauthorized_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorized_whenTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldUpdateProfile_whenTokenIsValid() throws Exception {
        String updateBody = """
                {
                    "bio": "Building ByYourSide",
                    "avatarUrl": "https://example.com/avatar.png"
                }
                """;

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Building ByYourSide"))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
                // el resto del perfil no deberia cambiar por una edicion parcial
                .andExpect(jsonPath("$.username").value("facu"));
    }

    @Test
    void shouldReturnUnauthorized_whenUpdatingProfileWithoutToken() throws Exception {
        String updateBody = """
                {
                    "bio": "intentando sin token"
                }
                """;

        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isUnauthorized());
    }
}