package com.byyourside.backend.auth;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthSessionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // El envio real de email nunca debe pegarle a Resend en tests -- se
    // mockea el punto de entrada al proveedor, no se hace network real.
    @MockBean
    private EmailService emailService;

    @BeforeEach
    void cleanUp() {
        passwordResetTokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userRepository.deleteAll();
    }

    private JsonNode register(String email, String password, String displayName) throws Exception {
        String body = """
                {"displayName": "%s", "email": "%s", "password": "%s"}
                """.formatted(displayName, email, password);

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response);
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response);
    }

    private String refreshBody(String refreshToken) {
        return """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);
    }

    private String changePasswordBody(String currentPassword, String newPassword) {
        return """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(currentPassword, newPassword);
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

    // Mismo algoritmo que AuthSessionService.hash(), duplicado a proposito
    // solo en el test: permite ubicar la fila persistida de un refresh token
    // sin exponer ningun metodo de hashing fuera del service en produccion.
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ============================================================
    // LOGIN / REGISTER
    // ============================================================

    @Test
    void shouldReturnAccessToken_onLogin() throws Exception { // A
        register("facu@example.com", "originalpass123", "Facu");

        JsonNode tokens = login("facu@example.com", "originalpass123");

        assertThat(tokens.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void shouldReturnRefreshToken_onLogin() throws Exception { // B
        register("facu@example.com", "originalpass123", "Facu");

        JsonNode tokens = login("facu@example.com", "originalpass123");

        String refreshToken = tokens.get("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();
        assertThat(refreshToken).isNotEqualTo(tokens.get("accessToken").asText());
    }

    @Test
    void shouldAllowAccessToken_toReachProtectedEndpoint() throws Exception { // C
        register("facu@example.com", "originalpass123", "Facu");
        JsonNode tokens = login("facu@example.com", "originalpass123");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + tokens.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("facu@example.com"));
    }

    @Test
    void shouldNeverPersistRefreshTokenInPlainText() throws Exception { // D
        register("facu@example.com", "originalpass123", "Facu");
        JsonNode tokens = login("facu@example.com", "originalpass123");
        String rawRefreshToken = tokens.get("refreshToken").asText();

        AuthSession session = authSessionRepository.findByTokenHash(hash(rawRefreshToken)).orElseThrow();

        assertThat(session.getTokenHash()).isNotEqualTo(rawRefreshToken);
        assertThat(session.getTokenHash()).hasSize(64); // hex de SHA-256
    }

    @Test
    void shouldCreateIndependentSession_perLogin() throws Exception { // E
        register("facu@example.com", "originalpass123", "Facu"); // sesion 1 (register)
        JsonNode deviceB = login("facu@example.com", "originalpass123"); // sesion 2 ("otro dispositivo")

        // Ambas sesiones son independientes: refrescar la de B no debe
        // afectar para nada a la de A (que sigue intacta, sin siquiera
        // haberse usado en este test).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(deviceB.get("refreshToken").asText())))
                .andExpect(status().isOk());

        long distinctFamilies = authSessionRepository.findAll().stream()
                .map(AuthSession::getFamilyId)
                .distinct()
                .count();
        assertThat(distinctFamilies).isEqualTo(2);
    }

    @Test
    void shouldCreateSessionAndReturnNewContract_onRegister() throws Exception { // F
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");

        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(tokens.get("username").asText()).isEqualTo("facu");
        assertThat(tokens.get("role").asText()).isEqualTo("USER");

        assertThat(authSessionRepository.count()).isEqualTo(1);
    }

    // ============================================================
    // ACCESS TOKEN
    // ============================================================

    @Test
    void shouldExposeConfiguredAccessTokenExpiration() throws Exception { // G
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");

        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900); // 15 min, nuevo default
    }

    @Test
    void shouldRejectInvalidJwt_onProtectedEndpoint() throws Exception { // H
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldKeepProtectedEndpointsWorking_withValidAccessToken() throws Exception { // I
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String accessToken = tokens.get("accessToken").asText();

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());
    }

    // ============================================================
    // REFRESH
    // ============================================================

    @Test
    void shouldReturnNewAccessToken_onValidRefresh() throws Exception { // J
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(tokens.get("refreshToken").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void shouldReturnNewRefreshToken_onValidRefresh() throws Exception { // K
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String oldRefreshToken = tokens.get("refreshToken").asText();

        String response = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(oldRefreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newRefreshToken = objectMapper.readTree(response).get("refreshToken").asText();
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);
    }

    @Test
    void shouldInvalidatePreviousRefreshToken_afterRotation() throws Exception { // L
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String oldRefreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(oldRefreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(oldRefreshToken)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAllowNewRefreshToken_toRefreshAgain() throws Exception { // M
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");

        String firstRefreshResponse = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(tokens.get("refreshToken").asText())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondGenRefreshToken = objectMapper.readTree(firstRefreshResponse).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(secondGenRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void shouldRejectUnknownRefreshToken() throws Exception { // N
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("never-issued-refresh-token")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void shouldRejectExpiredRefreshToken() throws Exception { // O
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String rawRefreshToken = tokens.get("refreshToken").asText();

        AuthSession session = authSessionRepository.findByTokenHash(hash(rawRefreshToken)).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        authSessionRepository.save(session);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has expired"));
    }

    @Test
    void shouldRejectRevokedRefreshToken() throws Exception { // P
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String rawRefreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));
    }

    @Test
    void shouldRejectMalformedRefreshToken() throws Exception { // Q
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("%%%not-a-real-token%%%")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    // ============================================================
    // REUSE DETECTION
    // ============================================================

    @Test
    void shouldDetectReuse_whenRotatedRefreshTokenIsPresentedAgain() throws Exception { // R
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String r1 = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isOk());

        // r1 ya fue rotado -- volver a presentarlo es la firma clasica de un
        // token copiado/robado.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Refresh token has already been used"));
    }

    @Test
    void shouldRevokeWholeFamily_whenReuseDetected() throws Exception { // S
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String r1 = tokens.get("refreshToken").asText();
        AuthSession firstSession = authSessionRepository.findByTokenHash(hash(r1)).orElseThrow();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isConflict());

        List<AuthSession> family = authSessionRepository.findAll().stream()
                .filter(s -> s.getFamilyId().equals(firstSession.getFamilyId()))
                .toList();
        assertThat(family).isNotEmpty();
        assertThat(family).allMatch(AuthSession::isRevoked);
    }

    @Test
    void shouldInvalidateDescendantToken_afterReuseDetected() throws Exception { // T
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String r1 = tokens.get("refreshToken").asText();

        String afterFirstRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String r2 = objectMapper.readTree(afterFirstRefresh).get("refreshToken").asText();

        String afterSecondRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r2)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String r3 = objectMapper.readTree(afterSecondRefresh).get("refreshToken").asText();

        // Reaparece r1 (la generacion original, ya rotada dos veces atras) --
        // esto debe revocar TODA la familia, incluido r3, que nunca llego a
        // usarse indebidamente.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));
    }

    // ============================================================
    // LOGOUT
    // ============================================================

    @Test
    void shouldRevokeSession_onLogout() throws Exception { // U
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String rawRefreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        AuthSession session = authSessionRepository.findByTokenHash(hash(rawRefreshToken)).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
    }

    @Test
    void shouldFailRefresh_afterLogout() throws Exception { // V
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String rawRefreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rawRefreshToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotRevokeOtherSession_onLogout() throws Exception { // W
        JsonNode sessionA = register("facu@example.com", "originalpass123", "Facu");
        JsonNode sessionB = login("facu@example.com", "originalpass123");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionA.get("refreshToken").asText())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionB.get("refreshToken").asText())))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnGenericSuccess_whenLoggingOutWithUnknownToken() throws Exception {
        // Idempotente / sin account enumeration: token inexistente no es un error.
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("never-issued-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ============================================================
    // CHANGE PASSWORD
    // ============================================================

    @Test
    void shouldRevokeAllSessions_onChangePassword() throws Exception { // X, Y
        JsonNode sessionA = register("facu@example.com", "originalpass123", "Facu"); // dispositivo 1
        JsonNode sessionB = login("facu@example.com", "originalpass123"); // dispositivo 2

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + sessionA.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        // Ninguna de las dos sesiones (ni siquiera la que hizo el cambio)
        // puede volver a emitir un access token nuevo via refresh.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionA.get("refreshToken").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionB.get("refreshToken").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));
    }

    @Test
    void shouldNotAffectOtherUser_onChangePassword() throws Exception { // Z
        JsonNode userA = register("userA@example.com", "originalpassA123", "UserA");
        JsonNode userB = register("userB@example.com", "originalpassB123", "UserB");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userA.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpassA123", "brandnewpassA456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(userB.get("refreshToken").asText())))
                .andExpect(status().isOk());
    }

    // ============================================================
    // RESET PASSWORD
    // ============================================================

    @Test
    void shouldRevokeAllSessions_onResetPassword() throws Exception { // AA, AB
        JsonNode sessionA = register("facu@example.com", "originalpass123", "Facu");
        JsonNode sessionB = login("facu@example.com", "originalpass123");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawResetToken = urlCaptor.getValue().substring(urlCaptor.getValue().indexOf("token=") + "token=".length());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawResetToken, "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionA.get("refreshToken").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(sessionB.get("refreshToken").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"));
    }

    @Test
    void shouldCreateValidSession_onLoginWithNewPassword_afterReset() throws Exception { // AC
        register("facu@example.com", "originalpass123", "Facu");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotPasswordBody("facu@example.com")))
                .andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq("facu@example.com"), eq("Facu"), urlCaptor.capture());
        String rawResetToken = urlCaptor.getValue().substring(urlCaptor.getValue().indexOf("token=") + "token=".length());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody(rawResetToken, "brandnewpass456")))
                .andExpect(status().isOk());

        JsonNode newSession = login("facu@example.com", "brandnewpass456");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(newSession.get("refreshToken").asText())))
                .andExpect(status().isOk());
    }

    // ============================================================
    // EMAIL FAILURE DOES NOT UNDO SESSION REVOCATION
    // ============================================================

    @Test
    void shouldKeepSessionsRevoked_whenConfirmationEmailFails_afterChangePassword() throws Exception { // AI
        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendPasswordChangedEmail(any(), any());

        JsonNode session = register("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + session.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        // La sesion sigue revocada pese al fallo del email de confirmacion --
        // y la contrasena nueva sigue siendo la vigente (no se revirtio nada).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.get("refreshToken").asText())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "facu@example.com", "password": "brandnewpass456"}
                                """))
                .andExpect(status().isOk());
    }

    // ============================================================
    // SEGURIDAD
    // ============================================================

    @Test
    void shouldNotExposeHashesOrRawTokens_inRefreshResponse() throws Exception { // AJ
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(tokens.get("refreshToken").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void shouldStoreOnlyHashedRefreshTokens_acrossFullRotationChain() throws Exception { // AL
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String r1 = tokens.get("refreshToken").asText();

        String afterFirst = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(r1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String r2 = objectMapper.readTree(afterFirst).get("refreshToken").asText();

        List<String> persistedHashes = authSessionRepository.findAll().stream()
                .map(AuthSession::getTokenHash)
                .toList();

        assertThat(persistedHashes).doesNotContain(r1, r2);
        assertThat(persistedHashes).allMatch(h -> h.length() == 64);
    }

    // ============================================================
    // CONCURRENCIA
    // ============================================================

    @Test
    void shouldOnlyAllowOneWinner_whenTwoConcurrentRotationsClaimSameToken() throws Exception { // AM
        JsonNode tokens = register("facu@example.com", "originalpass123", "Facu");
        String rawRefreshToken = tokens.get("refreshToken").asText();
        AuthSession session = authSessionRepository.findByTokenHash(hash(rawRefreshToken)).orElseThrow();

        // Transaccion explicita por hilo: cada hilo necesita su propio
        // limite transaccional para que el UPDATE atomico (claimForRotation)
        // se ejecute contra su propia conexion, exactamente como pasaria con
        // dos requests HTTP concurrentes reales.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claim = () -> transactionTemplate.execute(
                    status -> authSessionRepository.claimForRotation(session.getId()));

            Future<Integer> first = executor.submit(claim);
            Future<Integer> second = executor.submit(claim);

            int totalClaimed = first.get() + second.get();

            // Exactamente UNA de las dos llamadas concurrentes pudo haber
            // reclamado la fila (UPDATE atomico con WHERE rotated_at IS
            // NULL) -- nunca cero, nunca dos.
            assertThat(totalClaimed).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
