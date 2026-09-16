package com.byyourside.backend.report;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ReportControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User regularUser;
    private User adminUser;
    private String regularUserToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        reportRepository.deleteAll();
        userRepository.deleteAll();

        regularUser = registerUser("facu", "facu@example.com", UserRole.USER);
        adminUser = registerUser("admin", "admin@example.com", UserRole.ADMIN);

        regularUserToken = login("facu");
        adminToken = login("admin");
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
    void shouldCreateReport_whenAuthenticatedAsRegularUser() throws Exception {
        String body = """
                {
                    "targetType": "POST",
                    "targetId": "%s",
                    "reason": "SELF_HARM_RISK",
                    "description": "Me preocupa este contenido"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + regularUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("SELF_HARM_RISK"));
    }

    @Test
    void shouldReturnBadRequest_whenTargetIdIsMalformed() throws Exception {
        String body = """
                {
                    "targetType": "POST",
                    "targetId": "1234567",
                    "reason": "SPAM"
                }
                """;

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + regularUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnForbidden_whenRegularUserAccessesQueue() throws Exception {
        mockMvc.perform(get("/api/reports/queue")
                        .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnUnauthorized_whenAccessingQueueWithoutToken() throws Exception {
        mockMvc.perform(get("/api/reports/queue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnPendingQueue_whenAuthenticatedAsAdmin() throws Exception {
        createReport(ReportReason.SELF_HARM_RISK);

        mockMvc.perform(get("/api/reports/queue")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    void shouldPrioritizeSelfHarmRisk_overOlderReports() throws Exception {
        createReport(ReportReason.SPAM);
        Thread.sleep(10); // asegura createdAt distinto entre ambos reportes
        createReport(ReportReason.SELF_HARM_RISK);

        mockMvc.perform(get("/api/reports/queue")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // el SPAM se creo primero, pero SELF_HARM_RISK debe aparecer primero en la cola
                .andExpect(jsonPath("$.content[0].reason").value("SELF_HARM_RISK"))
                .andExpect(jsonPath("$.content[1].reason").value("SPAM"));
    }

    @Test
    void shouldResolveReport_whenAuthenticatedAsAdmin() throws Exception {
        Report report = createReport(ReportReason.HARASSMENT);

        mockMvc.perform(patch("/api/reports/{id}/resolve", report.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "ACTION_TAKEN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_TAKEN"))
                .andExpect(jsonPath("$.reviewedById").value(adminUser.getId().toString()));
    }

    @Test
    void shouldReturnForbidden_whenRegularUserResolvesReport() throws Exception {
        Report report = createReport(ReportReason.OTHER);

        mockMvc.perform(patch("/api/reports/{id}/resolve", report.getId())
                        .header("Authorization", "Bearer " + regularUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DISMISSED"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnConflict_whenResolvingAlreadyReviewedReport() throws Exception {
        Report report = createReport(ReportReason.HATE_SPEECH);
        report.setStatus(ReportStatus.ACTION_TAKEN);
        reportRepository.save(report);

        mockMvc.perform(patch("/api/reports/{id}/resolve", report.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DISMISSED"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturnBadRequest_whenResolvingBackToPending() throws Exception {
        Report report = createReport(ReportReason.SPAM);

        mockMvc.perform(patch("/api/reports/{id}/resolve", report.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PENDING"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private Report createReport(ReportReason reason) {
        return reportRepository.save(Report.builder()
                .reporter(regularUser)
                .targetType(ReportTargetType.POST)
                .targetId(UUID.randomUUID())
                .reason(reason)
                .build());
    }
}