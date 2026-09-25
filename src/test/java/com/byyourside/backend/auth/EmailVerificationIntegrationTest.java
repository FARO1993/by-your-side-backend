package com.byyourside.backend.auth;

import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class EmailVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository tokenRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User registerUser(String email) throws Exception {
        String body = """
                {"displayName": "Facu", "email": "%s", "password": "secretpass123"}
                """.formatted(email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        return userRepository.findByEmail(email).orElseThrow();
    }

    private String verifyEmailBody(String token) {
        return """
                {"token": "%s"}
                """.formatted(token);
    }

    // --- Registro ---

    @Test
    void shouldRegisterUserAsUnverified() throws Exception {
        User user = registerUser("facu@example.com");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
    }

    @Test
    void shouldCreateVerificationToken_whenRegistering() throws Exception {
        User user = registerUser("facu@example.com");

        EmailVerificationToken token = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();

        assertThat(token.isUsed()).isFalse();
        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void shouldNotBlockLogin_forUnverifiedUser() throws Exception {
        registerUser("facu@example.com");

        String loginBody = """
                {"email": "facu@example.com", "password": "secretpass123"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // --- Verificacion ---

    @Test
    void shouldVerifyEmail_whenTokenIsValid() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.emailVerifiedAt").isNotEmpty());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isTrue();
        assertThat(reloaded.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void shouldMarkTokenAsUsed_afterVerification() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk());

        EmailVerificationToken token = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void shouldRejectReusedToken() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Verification token has already been used"));
    }

    @Test
    void shouldStayIdempotent_whenUserAlreadyVerifiedButTokenStillValid() throws Exception {
        User user = registerUser("facu@example.com");
        String firstToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(firstToken)))
                .andExpect(status().isOk());

        User verifiedUser = userRepository.findById(user.getId()).orElseThrow();
        Instant firstVerifiedAt = verifiedUser.getEmailVerifiedAt();

        String secondToken = emailVerificationService.issue(verifiedUser);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(secondToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getEmailVerifiedAt()).isEqualTo(firstVerifiedAt);
    }

    // --- Errores ---

    @Test
    void shouldRejectUnknownToken() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody("never-issued-token")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification token"));
    }

    @Test
    void shouldRejectBlankToken() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.token").exists());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        EmailVerificationToken token = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        tokenRepository.save(token);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification token has expired"));
    }
}
