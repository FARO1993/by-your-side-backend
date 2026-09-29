package com.byyourside.backend.companion;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.mute.UserMute;
import com.byyourside.backend.mute.UserMuteRepository;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Backend Debt B4B.4: GET /api/users/{userId}/availability -- disponibilidad
// publica minima. Vive en el paquete `companion` (mismo criterio que
// StatusControllerIntegrationTest para /status: el endpoint esta declarado
// en UserController, pero el test acompaña al dominio/servicio que
// realmente implementa la logica, CompanionOfferingService.getPublicAvailability).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PublicAvailabilityIntegrationTest {

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
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private CompanionOfferingRepository companionOfferingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        companionOfferingRepository.deleteAll();
        followRequestRepository.deleteAll();
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

    private void giveOffering(User user, OfferingType type) {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(user).type(type)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
    }

    // ========================================================
    // F/G/H: publico, con cada OfferingType
    // ========================================================

    @Test
    void publicTarget_withListenOffering_isVisible() throws Exception { // F
        giveOffering(soumia, OfferingType.LISTEN);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("LISTEN"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void publicTarget_withTalkOffering_isVisible() throws Exception { // G
        giveOffering(soumia, OfferingType.TALK);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("TALK"));
    }

    @Test
    void publicTarget_withDistractOffering_isVisible() throws Exception { // H
        giveOffering(soumia, OfferingType.DISTRACT);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("DISTRACT"));
    }

    // ========================================================
    // D/E: sin Offering / expirada -> 200 null
    // ========================================================

    @Test
    void targetWithoutOffering_returnsNullBody() throws Exception { // D
        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void targetWithExpiredOffering_returnsNullBody() throws Exception { // E
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(soumia).type(OfferingType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ========================================================
    // I/J/K: owner, inexistente, sin auth
    // ========================================================

    @Test
    void owner_canQueryOwnAvailability() throws Exception { // I
        giveOffering(facu, OfferingType.LISTEN);

        mockMvc.perform(get("/api/users/{userId}/availability", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("LISTEN"));
    }

    @Test
    void nonexistentUser_returnsNotFound() throws Exception { // J
        mockMvc.perform(get("/api/users/{userId}/availability", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutAuth_returnsUnauthorized() throws Exception { // K
        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId()))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================
    // Block: unica regla que corta el acceso -- 404 en ambas direcciones
    // ========================================================

    @Test
    void whenViewerBlockedTarget_returnsNotFound() throws Exception { // A
        giveOffering(soumia, OfferingType.TALK);
        userBlockRepository.save(UserBlock.builder().blocker(facu).blocked(soumia).build());

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void whenTargetBlockedViewer_returnsNotFound() throws Exception { // B
        giveOffering(soumia, OfferingType.TALK);
        userBlockRepository.save(UserBlock.builder().blocker(soumia).blocked(facu).build());

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    // ========================================================
    // Mute: NUNCA es access control para el lookup directo -- solo excluye
    // de superficies agregadas (regresion incluida abajo).
    // ========================================================

    @Test
    void whenViewerMutedTarget_directLookupStillVisible() throws Exception { // C
        giveOffering(soumia, OfferingType.LISTEN);
        userMuteRepository.save(UserMute.builder().muter(facu).muted(soumia).build());

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("LISTEN"));
    }

    @Test
    void whenViewerMutedTarget_targetStillExcludedFromAggregatedSearch() throws Exception {
        // Regresion: confirma que el lookup directo (arriba) y la busqueda
        // agregada son dos caminos distintos con reglas de mute distintas
        // a proposito -- mute SI filtra la busqueda, pero NUNCA el lookup
        // directo por userId.
        giveOffering(soumia, OfferingType.LISTEN);
        userMuteRepository.save(UserMute.builder().muter(facu).muted(soumia).build());

        mockMvc.perform(get("/api/companion/offering").param("type", "LISTEN")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ========================================================
    // PRIVATE / REQUESTED: Offering activo es consentimiento especifico,
    // ProfileVisibility/FollowState NUNCA gatean este endpoint.
    // ========================================================

    @Test
    void privateProfile_withoutFollow_offeringStillVisible() throws Exception {
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        giveOffering(soumia, OfferingType.LISTEN);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("LISTEN"));

        // El perfil COMPLETO sigue respetando las reglas normales de
        // privacidad -- la disponibilidad no lo desbloquea. Perfil PRIVATE
        // sin follower aceptado siempre viaja con bio en null (vista
        // "limitada", Fase 9.1/9.3) -- eso no cambia por tener Offering activa.
        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.followState").value("NONE"));
    }

    @Test
    void privateProfile_withRequestedFollow_offeringStillVisible() throws Exception {
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        followRequestRepository.save(FollowRequest.builder().requester(facu).target(soumia).build());
        giveOffering(soumia, OfferingType.TALK);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.offeringType").value("TALK"));
    }

    // ========================================================
    // Response security: el DTO nunca expone mas que available/offeringType/expiresAt
    // ========================================================

    @Test
    void response_neverExposesMoreThanAllowedFields() throws Exception {
        giveOffering(soumia, OfferingType.LISTEN);

        mockMvc.perform(get("/api/users/{userId}/availability", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.need").doesNotExist())
                .andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.profileVisibility").doesNotExist())
                .andExpect(jsonPath("$.followState").doesNotExist())
                .andExpect(jsonPath("$.companionPreferences").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }
}
