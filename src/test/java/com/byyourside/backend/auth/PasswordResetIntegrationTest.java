package com.byyourside.backend.auth;

import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PasswordResetIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // El envio real de email nunca debe pegarle a Resend en tests -- se
    // mockea el punto de entrada al proveedor, no se hace network real.
    @MockBean
    private EmailService emailService;

    @BeforeEach
    void cleanUp() {
        tokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User createUser(String email, String password) {
        User user = User.builder()
                .username("facu")
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .displayName("Facu")
                .build();
        return userRepository.save(user);
    }

    private String forgotPasswordBody(String email) {
        return """
                {"email": "%s"}
                """.formatted(email);
    }

    private String resetPasswordBody(String token, String newPassword) {
        return """
                {"token": "%s", "newPassword": "%s"}
                """.formatted(token, newPassword);
    }

    private String loginBody(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
    }

    // Empuja hacia atras el created_at de TODOS los tokens de un usuario, para
    // poder probar el cooldown sin esperar tiempo real. Mismo motivo que en
    // EmailVerificationIntegrationTest: created_at es `updatable = false` en
    // la entidad, asi que hace falta un UPDATE directo.
    private void backdateAllTokensForUser(User user, long minutesAgo) {
        jdbcTemplate.update("UPDATE password_reset_tokens SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)), user.getId());
    }

    // Extrae el token crudo de la URL capturada del email (?token=...), igual
    // que en EmailVerificationIntegrationTest.
    private String extractToken(String url) {
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    // --- Forgot password ---

    @Test
    void shouldIssueTokenAndSendEmail_whenForgotPasswordForExistingUser() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).isPresent();
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), any());
    }

    @Test
    void shouldReturnGenericResponse_whenForgotPasswordForNonexistentEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("noexiste@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "If an account with that email exists, we've sent password reset instructions."));

        verifyNoInteractions(emailService);
    }

    @Test
    void shouldReturnSameGenericMessage_forExistingAndNonexistentEmail() throws Exception {
        createUser("facu@example.com", "originalpass123");

        var existingResult = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk())
                .andReturn();

        var nonexistentResult = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("noexiste@example.com")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(existingResult.getResponse().getContentAsString())
                .isEqualTo(nonexistentResult.getResponse().getContentAsString());
    }

    @Test
    void shouldNeverPersistRawResetTokenInPlainText() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        PasswordResetToken persisted = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();

        assertThat(persisted.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(persisted.getTokenHash()).hasSize(64); // hex de SHA-256
    }

    @Test
    void shouldNotLeakProviderFailureDetails_whenEmailProviderFails() throws Exception {
        createUser("facu@example.com", "originalpass123");
        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendPasswordResetEmail(any(), any(), any());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "If an account with that email exists, we've sent password reset instructions."));
    }

    @Test
    void shouldNotIssueAnotherToken_whenForgotPasswordWithinCooldown() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        PasswordResetToken firstToken = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();

        // Sin backdatear: sigue dentro del cooldown de 60s.
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "If an account with that email exists, we've sent password reset instructions."));

        PasswordResetToken latest = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).isEqualTo(firstToken.getId());
        assertThat(latest.isInvalidated()).isFalse();
        verify(emailService, times(1)).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), any());
    }

    @Test
    void shouldIssueNewTokenAndInvalidateOldOne_whenForgotPasswordOutsideCooldown() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        PasswordResetToken firstToken = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        backdateAllTokensForUser(user, 5); // fuera del cooldown de 60s

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        PasswordResetToken reloadedFirst = tokenRepository.findById(firstToken.getId()).orElseThrow();
        assertThat(reloadedFirst.isInvalidated()).isTrue();
        assertThat(reloadedFirst.isUsed()).isFalse();

        PasswordResetToken latest = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).isNotEqualTo(firstToken.getId());
        assertThat(latest.isInvalidated()).isFalse();

        verify(emailService, times(2)).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), any());
    }

    // --- Reset password ---

    @Test
    void shouldResetPassword_whenTokenIsValid() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void shouldRejectUnknownResetToken() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody("never-issued-token", "brandnewpass456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid password reset token"));
    }

    @Test
    void shouldRejectExpiredResetToken() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        PasswordResetToken token = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        tokenRepository.save(token);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password reset token has expired"));
    }

    @Test
    void shouldRejectReusedResetToken() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "yetanotherpass789")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Password reset token has already been used"));
    }

    @Test
    void shouldRejectInvalidatedResetToken_afterNewerRequest() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String firstRawToken = extractToken(urlCaptor.getValue());

        backdateAllTokensForUser(user, 5); // fuera del cooldown de 60s

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(firstRawToken, "brandnewpass456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Password reset token is no longer valid; a newer one may have been requested"));
    }

    @Test
    void shouldAllowReset_withNewTokenIssuedAfterInvalidation() throws Exception {
        User user = createUser("facu@example.com", "originalpass123");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());

        backdateAllTokensForUser(user, 5);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService, times(2)).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String newestRawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(newestRawToken, "brandnewpass456")))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectBlankToken() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody("", "brandnewpass456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.token").exists());
    }

    @Test
    void shouldRejectPasswordShorterThanEightCharacters() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
    }

    // --- Login posterior ---

    @Test
    void shouldNoLongerAuthenticateWithOldPassword_afterReset() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "originalpass123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAuthenticateWithNewPassword_afterReset() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "brandnewpass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // --- Email de confirmacion ---

    @Test
    void shouldSendPasswordChangedEmail_afterSuccessfulReset() throws Exception {
        createUser("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk());

        verify(emailService, times(1)).sendPasswordChangedEmail(eq("facu@example.com"), eq("Facu"));
    }

    @Test
    void shouldKeepPasswordReset_whenPasswordChangedEmailProviderFails() throws Exception {
        createUser("facu@example.com", "originalpass123");
        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendPasswordChangedEmail(any(), any());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawToken = extractToken(urlCaptor.getValue());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawToken, "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "brandnewpass456")))
                .andExpect(status().isOk());
    }
}
