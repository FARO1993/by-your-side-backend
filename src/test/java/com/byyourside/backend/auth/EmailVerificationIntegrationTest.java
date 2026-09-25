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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private EmailVerificationTokenRepository tokenRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

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

    private String resendVerificationBody(String email) {
        return """
                {"email": "%s"}
                """.formatted(email);
    }

    // Empuja hacia atras el created_at de TODOS los tokens de un usuario,
    // para poder probar el cooldown de resend sin esperar tiempo real (si
    // solo se backdatea el mas reciente, un token mas viejo sin backdatear
    // pasa a ser el "mas reciente" y sigue bloqueando el cooldown). Bulk
    // update en SQL: created_at es `updatable = false` en la entidad
    // (protege el registro normal de escritura accidental), asi que un
    // save() de entidad no alcanza -- hace falta un UPDATE directo, igual
    // que cualquier backfill administrativo real haria.
    private void backdateAllTokensForUser(User user, long minutesAgo) {
        jdbcTemplate.update("UPDATE email_verification_tokens SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)), user.getId());
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
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void shouldRequestVerificationEmail_withUrlThatActuallyVerifies_whenRegistering() throws Exception {
        registerUser("facu@example.com");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());

        String url = urlCaptor.getValue();
        assertThat(url).startsWith("http://localhost:5173/verify-email?token=");

        // El token viajo por el link del email, no por ningun otro canal --
        // lo usamos tal cual llegaria un usuario haciendo click, y debe
        // efectivamente verificar la cuenta.
        String rawTokenFromUrl = url.substring(url.indexOf("token=") + "token=".length());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawTokenFromUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void shouldNeverPersistRawTokenInPlainText() throws Exception {
        User user = registerUser("facu@example.com");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawTokenFromUrl = urlCaptor.getValue().substring(urlCaptor.getValue().indexOf("token=") + 6);

        EmailVerificationToken persisted = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();

        assertThat(persisted.getTokenHash()).isNotEqualTo(rawTokenFromUrl);
        assertThat(persisted.getTokenHash()).hasSize(64); // hex de SHA-256
    }

    @Test
    void shouldStillRegisterUser_whenVerificationEmailProviderFails() throws Exception {
        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendVerificationEmail(any(), any(), any());

        User user = registerUser("facu@example.com");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).isPresent();
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

        // La bienvenida se manda una sola vez, en la primera verificacion real.
        verify(emailService, times(1)).sendWelcomeEmail(eq("facu@example.com"), eq("Facu"));
    }

    @Test
    void shouldSendWelcomeEmail_exactlyOnce_whenVerifiedForFirstTime() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk());

        verify(emailService, times(1)).sendWelcomeEmail(eq("facu@example.com"), eq("Facu"));
    }

    @Test
    void shouldNotResendWelcomeEmail_whenReusingAlreadyConsumedToken() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isConflict());

        verify(emailService, times(1)).sendWelcomeEmail(any(), any());
    }

    @Test
    void shouldKeepVerification_whenWelcomeEmailProviderFails() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);

        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendWelcomeEmail(any(), any());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isTrue();
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

    // --- Resend verification ---

    @Test
    void shouldIssueNewTokenAndInvalidateOldOne_whenResendingForPendingUser() throws Exception {
        User user = registerUser("facu@example.com");
        EmailVerificationToken tokenFromRegister = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        backdateAllTokensForUser(user, 5); // fuera del cooldown de 60s

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resendVerificationBody("facu@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        EmailVerificationToken reloadedFirst = tokenRepository.findById(tokenFromRegister.getId()).orElseThrow();
        assertThat(reloadedFirst.isInvalidated()).isTrue();
        assertThat(reloadedFirst.isUsed()).isFalse();

        EmailVerificationToken latest = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).isNotEqualTo(tokenFromRegister.getId());
        assertThat(latest.isInvalidated()).isFalse();

        // Un email por el registro, otro por el resend.
        verify(emailService, times(2)).sendVerificationEmail(eq("facu@example.com"), eq("Facu"), any());
    }

    @Test
    void shouldRejectInvalidatedToken_onVerify() throws Exception {
        User user = registerUser("facu@example.com");
        String firstRawToken = emailVerificationService.issue(user);
        backdateAllTokensForUser(user, 5);

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resendVerificationBody("facu@example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(firstRawToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Verification token is no longer valid; a newer one may have been requested"));
    }

    @Test
    void shouldReturnGenericResponse_whenResendingForNonexistentEmail() throws Exception {
        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resendVerificationBody("noexiste@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(emailService);
    }

    @Test
    void shouldReturnGenericResponse_whenResendingForAlreadyVerifiedUser() throws Exception {
        User user = registerUser("facu@example.com");
        String rawToken = emailVerificationService.issue(user);
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyEmailBody(rawToken)))
                .andExpect(status().isOk());

        reset(emailService); // limpia las interacciones del registro/verificacion previas

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resendVerificationBody("facu@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(emailService);
    }

    @Test
    void shouldNotIssueAnotherToken_whenResendingWithinCooldown() throws Exception {
        User user = registerUser("facu@example.com");
        EmailVerificationToken tokenFromRegister = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();

        // Sin backdatear: el token de registro tiene segundos de antiguedad,
        // todavia dentro del cooldown de 60s.
        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resendVerificationBody("facu@example.com")))
                .andExpect(status().isOk());

        EmailVerificationToken latest = tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).isEqualTo(tokenFromRegister.getId());
        assertThat(latest.isInvalidated()).isFalse();

        // Solo el email del registro -- el resend dentro del cooldown no disparo otro.
        verify(emailService, times(1)).sendVerificationEmail(eq("facu@example.com"), eq("Facu"), any());
    }
}
