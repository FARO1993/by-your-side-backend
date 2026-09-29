package com.byyourside.backend.availability;

import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMute;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
import com.byyourside.backend.companion.CompanionOffering;
import com.byyourside.backend.companion.CompanionOfferingRepository;
import com.byyourside.backend.companion.OfferingType;
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

// Backend Debt B4B.3: este test valida el CONTRATO legacy de
// /api/availability/** (path, auth, shape de request/response), pero el
// fixture de datos usa CompanionOfferingRepository/CompanionOffering --
// companion_offerings es la unica fuente de verdad desde este PR, la tabla
// `availabilities` fue retirada (V16). Donde el mapping OfferingType ->
// CompanionIntent es lossy (MUSIC/WATCH_TOGETHER/LAUGH -> DISTRACT ->
// DISTRACTION), la expectativa del test refleja ese comportamiento
// documentado, no un valor arbitrario.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AvailabilityControllerIntegrationTest {

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

    private org.springframework.test.web.servlet.ResultActions setLegacyAvailability(String token, String intent) throws Exception {
        return mockMvc.perform(post("/api/availability")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intent\": \"" + intent + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions setNewOffering(String token, String type) throws Exception {
        return mockMvc.perform(put("/api/companion/offering")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"" + type + "\"}"));
    }

    // ========================================================
    // Contrato basico (preexistente, adaptado al nuevo fixture)
    // ========================================================

    @Test
    void shouldSetAvailability_whenAuthenticated() throws Exception { // A
        setLegacyAvailability(facuToken, "TALK")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("TALK"));
    }

    @Test
    void shouldReplacePreviousAvailability_withLossyMapping() throws Exception {
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated());
        setLegacyAvailability(facuToken, "MUSIC").andExpect(status().isCreated());

        // MUSIC -> OfferingType.DISTRACT -> se lee de vuelta como
        // DISTRACTION (lossy, ver LegacyAvailabilityMapper) -- no "MUSIC".
        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));

        assertEquals(1, companionOfferingRepository.count());
    }

    // ========================================================
    // A-F: mapping CompanionIntent -> OfferingType -> CompanionIntent
    // ========================================================

    @Test
    void shouldRoundTrip_talk_toTalk() throws Exception { // A
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("TALK"));
    }

    @Test
    void shouldRoundTrip_distraction_toDistraction() throws Exception { // B
        setLegacyAvailability(facuToken, "DISTRACTION").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void shouldMapWatchTogether_toLegacyDistraction() throws Exception { // C
        setLegacyAvailability(facuToken, "WATCH_TOGETHER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void shouldMapMusic_toLegacyDistraction() throws Exception { // D
        setLegacyAvailability(facuToken, "MUSIC").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void shouldMapLaugh_toLegacyDistraction() throws Exception { // E
        setLegacyAvailability(facuToken, "LAUGH").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void shouldRoundTrip_justCompany_toJustCompany() throws Exception { // F
        setLegacyAvailability(facuToken, "JUST_COMPANY").andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("JUST_COMPANY"));
    }

    // ========================================================
    // G-K: legacy /mine
    // ========================================================

    @Test
    void mine_afterSettingTalk_returnsTalk() throws Exception { // G
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("TALK"));
    }

    @Test
    void mine_afterSettingMusic_returnsDistraction() throws Exception { // H
        setLegacyAvailability(facuToken, "MUSIC").andExpect(status().isCreated());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void mine_returnsNullBody_whenNoActiveOffering() throws Exception { // I
        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void mine_returnsNullBody_whenOfferingExpired() throws Exception { // J
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(facu).type(OfferingType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void mine_returnsNullBody_afterDelete() throws Exception { // K
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated());

        mockMvc.perform(delete("/api/availability")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void delete_isIdempotent_whenNoneActive() throws Exception {
        mockMvc.perform(delete("/api/availability")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());
    }

    // ========================================================
    // L-T: busqueda legacy
    // ========================================================

    @Test
    void search_talk_findsTalkOffering() throws Exception { // L
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"))
                .andExpect(jsonPath("$[0].intent").value("TALK"));
    }

    @Test
    void search_distraction_findsDistractOffering() throws Exception { // M
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.DISTRACT)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "DISTRACTION")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].intent").value("DISTRACTION"));
    }

    @Test
    void search_music_alsoFindsDistractOffering() throws Exception { // N
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.DISTRACT)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        // MUSIC mapea al mismo OfferingType.DISTRACT que DISTRACTION -- el
        // adapter colapsa ambos intents legacy sobre la misma busqueda.
        mockMvc.perform(get("/api/availability").param("intent", "MUSIC")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"));
    }

    @Test
    void search_justCompany_findsListenOffering() throws Exception { // O
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "JUST_COMPANY")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].intent").value("JUST_COMPANY"));
    }

    @Test
    void search_excludesSelf() throws Exception { // P
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(facu).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_excludesExpired() throws Exception {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_excludesBlockedCandidate() throws Exception { // Q (bloqueo bilateral, sentido 1)
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        userBlockRepository.save(UserBlock.builder().blocker(facu).blocked(soumia).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_excludesCandidate_whenCandidateBlockedViewer() throws Exception { // Q (bloqueo bilateral, sentido 2)
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        userBlockRepository.save(UserBlock.builder().blocker(soumia).blocked(facu).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_excludesCandidate_whenViewerMutedCandidate() throws Exception { // R
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        userMuteRepository.save(UserMute.builder().muter(facu).muted(soumia).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void search_privateProfileWithActiveOffering_stillAppears() throws Exception { // S
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"));
    }

    @Test
    void search_capsAtTenCandidates() throws Exception { // T
        for (int i = 0; i < 11; i++) {
            User candidate = registerUser("legacycand" + i, "legacycand" + i + "@example.com");
            companionOfferingRepository.save(CompanionOffering.builder()
                    .user(candidate).type(OfferingType.TALK)
                    .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        }

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
    }

    // ========================================================
    // U-AA: interop nuevo <-> legacy (UNA sola source of truth)
    // ========================================================

    @Test
    void interop_newTalk_legacyMineReturnsTalk() throws Exception { // U
        setNewOffering(facuToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("TALK"));
    }

    @Test
    void interop_newListen_legacyMineReturnsJustCompany() throws Exception { // V
        setNewOffering(facuToken, "LISTEN").andExpect(status().isOk());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("JUST_COMPANY"));
    }

    @Test
    void interop_newDistract_legacyMineReturnsDistraction() throws Exception { // W
        setNewOffering(facuToken, "DISTRACT").andExpect(status().isOk());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("DISTRACTION"));
    }

    @Test
    void interop_legacyTalk_newMineReturnsTalk() throws Exception { // X
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TALK"));
    }

    @Test
    void interop_legacyMusic_newMineReturnsDistract() throws Exception { // Y
        setLegacyAvailability(facuToken, "MUSIC").andExpect(status().isCreated());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DISTRACT"));
    }

    @Test
    void interop_deleteLegacy_newMineIsEmpty() throws Exception { // Z
        setLegacyAvailability(facuToken, "TALK").andExpect(status().isCreated());

        mockMvc.perform(delete("/api/availability")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companion/offering/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void interop_deleteNew_legacyMineIsEmpty() throws Exception { // AA
        setNewOffering(facuToken, "TALK").andExpect(status().isOk());

        mockMvc.perform(delete("/api/companion/offering")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ========================================================
    // Chat / primer contacto (regresion, ver tambien ChatControllerIntegrationTest)
    // ========================================================

    @Test
    void shouldAllowStartingConversation_withAvailableStrangerButNotFollowed() throws Exception {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnForbidden_whenStrangerHasNoActiveAvailability() throws Exception {
        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    // ========================================================
    // Concurrencia: el adapter legacy hereda la misma proteccion de B4B.2
    // (UNIQUE(user_id) + CompanionOfferingWriter), no reimplementa nada.
    // ========================================================

    @Test
    void shouldNeverLeaveTwoOfferings_whenTwoLegacyPostsRaceConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> postTalk = () -> {
            ready.countDown();
            start.await();
            return setLegacyAvailability(facuToken, "TALK").andReturn().getResponse().getStatus();
        };
        Callable<Integer> postDistraction = () -> {
            ready.countDown();
            start.await();
            return setLegacyAvailability(facuToken, "DISTRACTION").andReturn().getResponse().getStatus();
        };

        try {
            Future<Integer> resultTalk = executor.submit(postTalk);
            Future<Integer> resultDistraction = executor.submit(postDistraction);

            ready.await();
            start.countDown();

            assertEquals(201, resultTalk.get(10, TimeUnit.SECONDS));
            assertEquals(201, resultDistraction.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }

        assertEquals(1, companionOfferingRepository.count());
    }
}
