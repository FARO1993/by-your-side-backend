package com.byyourside.backend.companion;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Backend Debt B4B.5: Stable Companion Preferences -- "como suelo estar
// para otros". Cubre CRUD propio (GET/PATCH mine), integracion con
// PublicUserProfileResponse (visible/oculto segun las reglas normales de
// perfil, NUNCA segun Offering/Need), constraints de DB y concurrencia del
// reemplazo completo.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CompanionPreferenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private CompanionPreferenceRepository companionPreferenceRepository;

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
        companionPreferenceRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
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

    private void patchPreferences(String token, String... types) throws Exception {
        StringBuilder json = new StringBuilder("{\"types\": [");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) json.append(", ");
            json.append("\"").append(types[i]).append("\"");
        }
        json.append("]}");

        mockMvc.perform(patch("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isOk());
    }

    // ========================================================
    // G-Q: API propia (GET/PATCH mine)
    // ========================================================

    @Test
    void getMine_withNoPreferences_returnsEmptyList() throws Exception { // G
        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").isArray())
                .andExpect(jsonPath("$.types").isEmpty());
    }

    @Test
    void patch_singleListen() throws Exception { // H
        patchPreferences(facuToken, "LISTEN");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("LISTEN")));
    }

    @Test
    void patch_singleTalk() throws Exception { // I
        patchPreferences(facuToken, "TALK");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("TALK")));
    }

    @Test
    void patch_singleDistract() throws Exception { // J
        patchPreferences(facuToken, "DISTRACT");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("DISTRACT")));
    }

    @Test
    void patch_multipleTypes_inDeclarationOrder() throws Exception { // K
        // Insertado deliberadamente en un orden distinto al de declaracion
        // del enum -- la respuesta debe seguir LISTEN, TALK, DISTRACT.
        patchPreferences(facuToken, "DISTRACT", "LISTEN", "TALK");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("LISTEN", "TALK", "DISTRACT")));
    }

    @Test
    void patch_replacesEntireSet() throws Exception { // L
        patchPreferences(facuToken, "LISTEN", "TALK");
        patchPreferences(facuToken, "DISTRACT");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("DISTRACT")));

        assertEquals(1, companionPreferenceRepository.count());
    }

    @Test
    void patch_emptyArray_clearsAllPreferences() throws Exception { // M
        patchPreferences(facuToken, "LISTEN", "TALK", "DISTRACT");
        patchPreferences(facuToken);

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").isEmpty());

        assertEquals(0, companionPreferenceRepository.count());
    }

    @Test
    void patch_duplicateTypes_areNormalized() throws Exception { // N
        patchPreferences(facuToken, "LISTEN", "LISTEN", "TALK");

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("LISTEN", "TALK")));

        assertEquals(2, companionPreferenceRepository.count());
    }

    @Test
    void patch_invalidEnumValue_returnsBadRequest() throws Exception { // O
        mockMvc.perform(patch("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"types": ["NOT_A_REAL_TYPE"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_missingTypes_returnsBadRequest() throws Exception { // P (faltante)
        mockMvc.perform(patch("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_nullTypes_returnsBadRequest() throws Exception { // P (null)
        mockMvc.perform(patch("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"types": null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMine_withoutAuth_returnsUnauthorized() throws Exception { // Q
        mockMvc.perform(get("/api/users/me/companion-preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patch_withoutAuth_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/users/me/companion-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"types": ["LISTEN"]}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================
    // R-V: constraints de DB
    // ========================================================

    @Test
    void uniqueConstraint_preventsDuplicateTypeForSameUser() { // R
        companionPreferenceRepository.saveAndFlush(CompanionPreference.builder()
                .user(facu).type(CompanionPreferenceType.LISTEN).build());

        assertThrows(DataIntegrityViolationException.class, () ->
                companionPreferenceRepository.saveAndFlush(CompanionPreference.builder()
                        .user(facu).type(CompanionPreferenceType.LISTEN).build()));
    }

    @Test
    void sameUser_canHaveAllThreeTypesSimultaneously() throws Exception { // S
        patchPreferences(facuToken, "LISTEN", "TALK", "DISTRACT");

        assertEquals(3, companionPreferenceRepository.count());
    }

    @Test
    void differentUsers_canHaveTheSameType() throws Exception { // T
        patchPreferences(facuToken, "LISTEN");
        patchPreferences(soumiaToken, "LISTEN");

        assertEquals(2, companionPreferenceRepository.count());
    }

    @Test
    void replace_neverLeavesOldRows() throws Exception { // U
        patchPreferences(facuToken, "LISTEN", "TALK");
        patchPreferences(facuToken, "TALK", "DISTRACT");

        assertEquals(2, companionPreferenceRepository.count());
        assertEquals(Set.of(CompanionPreferenceType.TALK, CompanionPreferenceType.DISTRACT),
                companionPreferenceRepository.findTypesByUserId(facu.getId()));
    }

    @Test
    void emptyReplace_leavesZeroRows() throws Exception { // V
        patchPreferences(facuToken, "LISTEN", "TALK", "DISTRACT");
        patchPreferences(facuToken);

        assertEquals(0, companionPreferenceRepository.count());
    }

    // ========================================================
    // Orden determinista (independiente del orden fisico/de insercion)
    // ========================================================

    @Test
    void response_isAlwaysInEnumDeclarationOrder_regardlessOfInsertionOrder() throws Exception {
        // Insertadas directo por repositorio en orden DISTRACT, LISTEN,
        // TALK -- la respuesta debe igual seguir el orden de declaracion
        // del enum, nunca el orden fisico/de insercion en la DB.
        companionPreferenceRepository.save(CompanionPreference.builder()
                .user(facu).type(CompanionPreferenceType.DISTRACT).build());
        companionPreferenceRepository.save(CompanionPreference.builder()
                .user(facu).type(CompanionPreferenceType.LISTEN).build());
        companionPreferenceRepository.save(CompanionPreference.builder()
                .user(facu).type(CompanionPreferenceType.TALK).build());

        mockMvc.perform(get("/api/users/me/companion-preferences")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(org.hamcrest.Matchers.contains("LISTEN", "TALK", "DISTRACT")));
    }

    // ========================================================
    // Concurrencia: dos PATCH concurrentes nunca dejan un set mezclado
    // ========================================================

    @Test
    void concurrentPatches_neverLeaveAMergedSet() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> patchA = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(patch("/api/users/me/companion-preferences")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"types": ["LISTEN", "TALK"]}
                                    """))
                    .andReturn().getResponse().getStatus();
        };
        Callable<Integer> patchB = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(patch("/api/users/me/companion-preferences")
                            .header("Authorization", "Bearer " + facuToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"types": ["DISTRACT"]}
                                    """))
                    .andReturn().getResponse().getStatus();
        };

        int statusA;
        int statusB;
        try {
            Future<Integer> resultA = executor.submit(patchA);
            Future<Integer> resultB = executor.submit(patchB);

            ready.await();
            start.countDown();

            statusA = resultA.get(15, TimeUnit.SECONDS);
            statusB = resultB.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertEquals(200, statusA);
        assertEquals(200, statusB);

        // Invariante central: el resultado final debe ser EXACTAMENTE el
        // set de una de las dos requests -- nunca una mezcla de ambas
        // (ej. [LISTEN, TALK, DISTRACT] seria una mezcla invalida, ninguna
        // de las dos requests pidio eso).
        Set<CompanionPreferenceType> finalState = companionPreferenceRepository.findTypesByUserId(facu.getId());
        boolean isSetA = finalState.equals(Set.of(CompanionPreferenceType.LISTEN, CompanionPreferenceType.TALK));
        boolean isSetB = finalState.equals(Set.of(CompanionPreferenceType.DISTRACT));
        assertTrue(isSetA || isSetB, "El estado final debe ser exactamente el set A o el set B, nunca una mezcla: " + finalState);
    }

    // ========================================================
    // A-F: integracion con PublicUserProfileResponse
    // ========================================================

    @Test
    void publicProfile_public_withPreferences_showsThem() throws Exception { // A
        patchPreferences(soumiaToken, "LISTEN", "TALK");

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").value(org.hamcrest.Matchers.contains("LISTEN", "TALK")));
    }

    @Test
    void publicProfile_public_withoutPreferences_showsEmptyList() throws Exception { // B
        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").isArray())
                .andExpect(jsonPath("$.companionPreferences").isEmpty());
    }

    @Test
    void publicProfile_privateWithAcceptedFollow_showsPreferences() throws Exception { // C
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        patchPreferences(soumiaToken, "DISTRACT");

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").value(org.hamcrest.Matchers.contains("DISTRACT")));
    }

    @Test
    void publicProfile_privateWithoutFollow_hidesPreferences_asNullNotEmpty() throws Exception { // D
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        patchPreferences(soumiaToken, "LISTEN");

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void publicProfile_privateWithRequestedFollow_hidesPreferences() throws Exception { // E
        soumia.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(soumia);
        followRequestRepository.save(FollowRequest.builder().requester(facu).target(soumia).build());
        patchPreferences(soumiaToken, "LISTEN");

        mockMvc.perform(get("/api/users/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void publicProfile_ownerPrivate_alwaysSeesOwnPreferences() throws Exception { // F
        facu.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(facu);
        patchPreferences(facuToken, "TALK");

        mockMvc.perform(get("/api/users/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companionPreferences").value(org.hamcrest.Matchers.contains("TALK")));
    }
}
