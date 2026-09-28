package com.byyourside.backend.companion;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CompanionNeedControllerIntegrationTest {

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
    private UserMuteRepository userMuteRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private CompanionNeedRepository companionNeedRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        companionNeedRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email) {
        User user = User.builder()
                .username(username).email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username).role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload(username + "@example.com", "secretpass123"));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private record LoginPayload(String email, String password) {
    }

    @Test
    void shouldSetNeed_whenAuthenticated() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TALK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TALK"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void shouldAcceptEveryNeedType() throws Exception {
        for (String type : new String[]{"LISTEN_TO_ME", "TALK", "GET_OPINION", "DISTRACTION", "JUST_COMPANY"}) {
            mockMvc.perform(put("/api/companion/need")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\": \"" + type + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value(type));
        }
    }

    @Test
    void shouldRejectInvalidNeedType() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "NOT_A_REAL_TYPE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingNeedType() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireAuthentication_toSetNeed() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TALK"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReplacePreviousNeed_whenSettingNewOne() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TALK"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "DISTRACTION"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DISTRACTION"));

        // Solo una fila activa por usuario -- la anterior fue reemplazada, no acumulada.
        org.junit.jupiter.api.Assertions.assertEquals(1, companionNeedRepository.count());
    }

    @Test
    void shouldReturnNullBody_onMine_whenNoActiveNeed() throws Exception {
        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldExcludeExpiredNeed_fromMine() throws Exception {
        companionNeedRepository.save(CompanionNeed.builder()
                .user(facu).type(NeedType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldCancelNeed() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TALK"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldCancelNeed_idempotently_whenNoneActive() throws Exception {
        mockMvc.perform(delete("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldScopeNeed_toOwnerOnly() throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TALK"}
                                """))
                .andExpect(status().isOk());

        // soumia nunca ve el Need de facu -- no existe ningun endpoint que
        // exponga el Need de otro usuario (decision B4A #10), y /mine
        // siempre resuelve contra el principal autenticado.
        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // Ajuste de integridad de datos: UNIQUE(user_id) a nivel DB (V14) es la
    // ultima defensa contra dos PUT concurrentes del mismo usuario. Dispara
    // dos requests reales en threads distintos, sincronizados con un latch
    // para maximizar la ventana de carrera contra el Postgres real de
    // Testcontainers (no un mock), y verifica la invariante al finalizar.
    @Test
    void shouldNeverLeaveTwoNeeds_whenTwoPutRequestsRaceConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> putTalk = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(put("/api/companion/need")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type": "TALK"}
                                    """))
                    .andReturn().getResponse().getStatus();
        };
        Callable<Integer> putDistraction = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(put("/api/companion/need")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type": "DISTRACTION"}
                                    """))
                    .andReturn().getResponse().getStatus();
        };

        try {
            Future<Integer> resultTalk = executor.submit(putTalk);
            Future<Integer> resultDistraction = executor.submit(putDistraction);

            ready.await();
            start.countDown();

            int statusTalk = resultTalk.get(10, TimeUnit.SECONDS);
            int statusDistraction = resultDistraction.get(10, TimeUnit.SECONDS);

            // Ninguna de las dos requests concurrentes debe propagar un 500
            // ni quedar colgada -- el reintento ante la carrera de
            // UNIQUE(user_id) las resuelve a ambas con 200.
            assertEquals(200, statusTalk);
            assertEquals(200, statusDistraction);
        } finally {
            executor.shutdown();
        }

        // Invariante central: jamas quedan dos CompanionNeed para el mismo
        // usuario, sin importar el orden real de ejecucion de los threads.
        assertEquals(1, companionNeedRepository.count());

        // Y ese unico Need queda en un estado valido y consultable --
        // exactamente el tipo de la request que efectivamente gano la
        // carrera (TALK o DISTRACTION, cualquiera de los dos es correcto).
        mockMvc.perform(get("/api/companion/need/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").exists());
    }
}
