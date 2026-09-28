package com.byyourside.backend.companion;

import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMute;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
import com.byyourside.backend.user.ProfileVisibility;
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
import org.springframework.test.web.servlet.ResultActions;
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
class CompanionOfferingControllerIntegrationTest {

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
    private CompanionOfferingRepository companionOfferingRepository;

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
        companionOfferingRepository.deleteAll();
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

    private ResultActions putOffering(String token, String type) throws Exception {
        return mockMvc.perform(put("/api/companion/offering")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"" + type + "\"}"));
    }

    private void setNeed(String token, String type) throws Exception {
        mockMvc.perform(put("/api/companion/need")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"" + type + "\"}"))
                .andExpect(status().isOk());
    }

    // ========================================================
    // A-N: CRUD
    // ========================================================

    @Test
    void shouldSetOffering_typeListen() throws Exception {
        putOffering(facuToken, "LISTEN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LISTEN"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void shouldSetOffering_typeTalk() throws Exception {
        putOffering(facuToken, "TALK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TALK"));
    }

    @Test
    void shouldSetOffering_typeDistract() throws Exception {
        putOffering(facuToken, "DISTRACT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DISTRACT"));
    }

    @Test
    void shouldRejectInvalidOfferingType() throws Exception {
        mockMvc.perform(put("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "NOT_A_REAL_TYPE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingOfferingType() throws Exception {
        mockMvc.perform(put("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireAuthentication_toSetOffering() throws Exception {
        mockMvc.perform(put("/api/companion/offering")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "LISTEN"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReplacePreviousOffering_whenSettingNewOne() throws Exception {
        putOffering(facuToken, "LISTEN").andExpect(status().isOk());
        putOffering(facuToken, "DISTRACT").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DISTRACT"));

        // Una sola fila final -- la anterior fue reemplazada, no acumulada.
        assertEquals(1, companionOfferingRepository.count());
    }

    @Test
    void shouldReturnActiveOffering_onMine() throws Exception {
        putOffering(facuToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TALK"));
    }

    @Test
    void shouldReturnNullBody_onMine_whenNoActiveOffering() throws Exception {
        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldExcludeExpiredOffering_fromMine() throws Exception {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(facu).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldCancelOffering() throws Exception {
        putOffering(facuToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(delete("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldCancelOffering_idempotently_whenNoneActive() throws Exception {
        mockMvc.perform(delete("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());
    }

    // ========================================================
    // Concurrencia: UNIQUE(user_id) + CompanionOfferingWriter (REQUIRES_NEW)
    // deben sobrevivir a dos PUT reales concurrentes -- mismo test que
    // CompanionNeedControllerIntegrationTest, para el dominio Offering.
    // ========================================================

    @Test
    void shouldNeverLeaveTwoOfferings_whenTwoPutRequestsRaceConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> putListen = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(put("/api/companion/offering")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type": "LISTEN"}
                                    """))
                    .andReturn().getResponse().getStatus();
        };
        Callable<Integer> putDistract = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(put("/api/companion/offering")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type": "DISTRACT"}
                                    """))
                    .andReturn().getResponse().getStatus();
        };

        try {
            Future<Integer> resultListen = executor.submit(putListen);
            Future<Integer> resultDistract = executor.submit(putDistract);

            ready.await();
            start.countDown();

            int statusListen = resultListen.get(10, TimeUnit.SECONDS);
            int statusDistract = resultDistract.get(10, TimeUnit.SECONDS);

            assertEquals(200, statusListen);
            assertEquals(200, statusDistract);
        } finally {
            executor.shutdown();
        }

        assertEquals(1, companionOfferingRepository.count());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").exists());
    }

    // ========================================================
    // O-Y: busqueda por tipo exacto
    // ========================================================

    @Test
    void shouldFindExactTypeMatch() throws Exception {
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"))
                .andExpect(jsonPath("$[0].offeringType").value("LISTEN"));
    }

    @Test
    void shouldNotReturnDifferentType() throws Exception {
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeSelf_fromSearch() throws Exception {
        putOffering(facuToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeExpired_fromSearch() throws Exception {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeCandidate_whenViewerBlockedCandidate() throws Exception {
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());
        userBlockRepository.save(UserBlock.builder().blocker(facu).blocked(soumia).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeCandidate_whenCandidateBlockedViewer() throws Exception {
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());
        userBlockRepository.save(UserBlock.builder().blocker(soumia).blocked(facu).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeCandidate_whenViewerMutedCandidate() throws Exception {
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());
        userMuteRepository.save(UserMute.builder().muter(facu).muted(soumia).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void muteIsUnilateral_viewerStillAppearsForTheUserItMuted() throws Exception {
        // facu muteo a soumia -- pero facu sigue apareciendo con total
        // normalidad en la busqueda DE soumia (mute nunca es reciproco).
        putOffering(facuToken, "LISTEN").andExpect(status().isOk());
        userMuteRepository.save(UserMute.builder().muter(facu).muted(soumia).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("facu"));
    }

    @Test
    void shouldAppearInSearch_whenPrivateProfileWithoutFollow_butActiveOffering() throws Exception {
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());

        // facu y soumia no tienen ninguna relacion de follow -- el Offering
        // activo es el consentimiento especifico para aparecer aca, sin
        // requerir accepted follower (decision B4A #3).
        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"));
    }

    @Test
    void shouldNotAppear_whenNoActiveOffering() throws Exception {
        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldCapAtTenCandidates() throws Exception {
        for (int i = 0; i < 11; i++) {
            User candidate = registerUser("candidate" + i, "candidate" + i + "@example.com");
            companionOfferingRepository.save(CompanionOffering.builder()
                    .user(candidate).type(OfferingType.LISTEN)
                    .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        }

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
    }

    // ========================================================
    // Z-AH: /compatible (Need -> Offering)
    // ========================================================

    @Test
    void compatible_needListenToMe_findsListenOffering() throws Exception {
        setNeed(facuToken, "LISTEN_TO_ME");
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offeringType").value("LISTEN"));
    }

    @Test
    void compatible_needTalk_findsTalkOffering() throws Exception {
        setNeed(facuToken, "TALK");
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offeringType").value("TALK"));
    }

    @Test
    void compatible_needGetOpinion_findsTalkOffering() throws Exception {
        setNeed(facuToken, "GET_OPINION");
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offeringType").value("TALK"));
    }

    @Test
    void compatible_needDistraction_findsDistractOffering() throws Exception {
        setNeed(facuToken, "DISTRACTION");
        putOffering(soumiaToken, "DISTRACT").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offeringType").value("DISTRACT"));
    }

    @Test
    void compatible_needJustCompany_findsListenOffering() throws Exception {
        setNeed(facuToken, "JUST_COMPANY");
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offeringType").value("LISTEN"));
    }

    @Test
    void compatible_returnsEmptyList_whenNoActiveNeed() throws Exception {
        putOffering(soumiaToken, "LISTEN").andExpect(status().isOk());

        // Sin Need activo no hay ningun criterio con el que buscar -- 200
        // con lista vacia, nunca 404 (decision de diseño explicita, no un
        // recurso inexistente).
        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void compatible_returnsEmptyList_whenNeedExpired() throws Exception {
        companionNeedRepository.save(CompanionNeed.builder()
                .user(facu).type(NeedType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void compatible_appliesBlockAndMute() throws Exception {
        setNeed(facuToken, "TALK");
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());
        userBlockRepository.save(UserBlock.builder().blocker(facu).blocked(soumia).build());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void compatible_neverReturnsSelf() throws Exception {
        setNeed(facuToken, "TALK");
        putOffering(facuToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void compatible_neverExposesNeedInCandidateDto() throws Exception {
        setNeed(facuToken, "TALK");
        putOffering(soumiaToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/companion/offering/compatible")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].need").doesNotExist())
                .andExpect(jsonPath("$[0].needType").doesNotExist());
    }
}
