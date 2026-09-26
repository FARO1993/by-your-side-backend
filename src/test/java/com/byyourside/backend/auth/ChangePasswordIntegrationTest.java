package com.byyourside.backend.auth;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChangePasswordIntegrationTest {

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
    private ObjectMapper objectMapper;

    // El envio real de email nunca debe pegarle a Resend en tests -- se
    // mockea el punto de entrada al proveedor, no se hace network real.
    @MockBean
    private EmailService emailService;

    @BeforeEach
    void cleanUp() {
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String registerAndLogin(String email, String password, String displayName) throws Exception {
        String registerBody = """
                {"displayName": "%s", "email": "%s", "password": "%s"}
                """.formatted(displayName, email, password);

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private String changePasswordBody(String currentPassword, String newPassword) {
        return """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(currentPassword, newPassword);
    }

    private String loginBody(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
    }

    // --- Autenticacion ---

    @Test
    void shouldRejectRequest_whenNoTokenProvided() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(emailService);
    }

    @Test
    void shouldRejectRequest_whenTokenIsInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer invalid.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(emailService);
    }

    @Test
    void shouldAllowAccess_whenTokenIsValid() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());
    }

    // --- currentPassword ---

    @Test
    void shouldRejectChange_whenCurrentPasswordIsIncorrect() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("wrongcurrentpass", "brandnewpass456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    void shouldNotChangePassword_whenCurrentPasswordIsIncorrect() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("wrongcurrentpass", "brandnewpass456")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "originalpass123")))
                .andExpect(status().isOk());
    }

    @Test
    void shouldNotSendEmail_whenCurrentPasswordIsIncorrect() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("wrongcurrentpass", "brandnewpass456")))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendPasswordChangedEmail(any(), any());
    }

    // --- newPassword ---

    @Test
    void shouldRejectChange_whenNewPasswordIsTooShort() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());

        verify(emailService, never()).sendPasswordChangedEmail(any(), any());
    }

    @Test
    void shouldRejectChange_whenNewPasswordEqualsCurrentPassword() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "originalpass123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password must be different from the current password"));
    }

    @Test
    void shouldNotChangePassword_whenNewPasswordEqualsCurrentPassword() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "originalpass123")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "originalpass123")))
                .andExpect(status().isOk());
    }

    @Test
    void shouldNotSendEmail_whenNewPasswordEqualsCurrentPassword() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "originalpass123")))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendPasswordChangedEmail(any(), any());
    }

    // --- Cambio exitoso ---

    @Test
    void shouldReturnSuccessMessage_whenChangeIsValid() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));
    }

    @Test
    void shouldStorePasswordEncoded_neverPlainText() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        User user = userRepository.findByEmail("facu@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo("brandnewpass456");
        assertThat(user.getPasswordHash()).startsWith("$2"); // prefijo BCrypt
    }

    @Test
    void shouldNoLongerAuthenticateWithOldPassword_afterChange() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "originalpass123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAuthenticateWithNewPassword_afterChange() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "brandnewpass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // --- Email de confirmacion ---

    @Test
    void shouldSendPasswordChangedEmail_exactlyOnce_afterSuccessfulChange() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        verify(emailService, times(1)).sendPasswordChangedEmail(eq("facu@example.com"), eq("Facu"));
    }

    @Test
    void shouldKeepPasswordChange_whenEmailProviderFails() throws Exception {
        doThrow(new EmailDeliveryException("boom", new RuntimeException("Resend down")))
                .when(emailService).sendPasswordChangedEmail(any(), any());

        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("facu@example.com", "brandnewpass456")))
                .andExpect(status().isOk());
    }

    // --- Respuesta sin datos sensibles ---

    @Test
    void shouldNotLeakSensitiveData_inSuccessResponse() throws Exception {
        String token = registerAndLogin("facu@example.com", "originalpass123", "Facu");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("originalpass123", "brandnewpass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.newPassword").doesNotExist())
                .andExpect(jsonPath("$.currentPassword").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist());
    }

    // --- Aislamiento entre usuarios ---

    @Test
    void shouldOnlyChangeOwnPassword_evenWithForeignIdentifiersInBody() throws Exception {
        String tokenA = registerAndLogin("userA@example.com", "originalpassA123", "UserA");
        registerAndLogin("userB@example.com", "originalpassB123", "UserB");

        // El DTO no tiene ningun campo de identidad (userId/email/username):
        // aunque se cuelen claves extra en el JSON, Jackson las ignora y el
        // cambio solo puede aplicar sobre el usuario resuelto desde el JWT.
        String bodyWithForeignFields = """
                {"currentPassword": "originalpassA123", "newPassword": "brandnewpassA456",
                 "email": "userB@example.com", "userId": "should-be-ignored"}
                """;

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithForeignFields))
                .andExpect(status().isOk());

        // A cambio correctamente.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("userA@example.com", "brandnewpassA456")))
                .andExpect(status().isOk());

        // B no se vio afectado en absoluto.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("userB@example.com", "originalpassB123")))
                .andExpect(status().isOk());
    }
}
