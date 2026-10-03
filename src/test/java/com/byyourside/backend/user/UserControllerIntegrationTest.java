package com.byyourside.backend.user;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class UserControllerIntegrationTest {

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
    private ObjectMapper objectMapper;

    private String token;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @BeforeEach
    void setUp() throws Exception {
        followRequestRepository.deleteAll();
        followRepository.deleteAll();   // ← primero: borra lo que referencia a users
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();     // ← ahora sí, sin FKs pendientes

        String registerBody = """
                {
                    "username": "facu",
                    "email": "facu@example.com",
                    "password": "secretpass123",
                    "displayName": "Facu"
                }
                """;

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        token = objectMapper.readTree(response).get("accessToken").asText();
    }

    private User registerUser(String username, String email, UserRole role) throws Exception {
        String registerBody = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "secretpass123",
                    "displayName": "%s"
                }
                """.formatted(username, email, username);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        User user = userRepository.findByUsername(username).orElseThrow();

        if (role != UserRole.USER) {
            user.setRole(role);
            user = userRepository.save(user);
        }

        return user;
    }

    @Test
    void shouldReturnCurrentUser_whenTokenIsValid() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("facu"))
                .andExpect(jsonPath("$.email").value("facu@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void shouldReturnUnauthorized_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorized_whenTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldUpdateProfile_whenTokenIsValid() throws Exception {
        String updateBody = """
                {
                    "bio": "Building ByYourSide",
                    "avatarUrl": "https://example.com/avatar.png"
                }
                """;

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Building ByYourSide"))
                // Ya no se puede poner una foto por URL: el campo se ignora.
                .andExpect(jsonPath("$.avatarId").doesNotExist())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.username").value("facu"));
    }

    // ============================================================
    // PATCH /me — VALIDACION (Backend Debt B2)
    // ============================================================

    @Test
    void patch_updatesDisplayName() throws Exception { // A
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Facundo R"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Facundo R"));
    }

    @Test
    void patch_updatesBio() throws Exception { // B
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "mi nueva bio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("mi nueva bio"));
    }

    @Test
    void patch_updatesBothDisplayNameAndBio() throws Exception { // C
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Facundo R", "bio": "mi nueva bio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Facundo R"))
                .andExpect(jsonPath("$.bio").value("mi nueva bio"));
    }

    @Test
    void patch_partialDisplayName_keepsExistingBio() throws Exception { // D
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "bio original"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Nombre nuevo"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Nombre nuevo"))
                .andExpect(jsonPath("$.bio").value("bio original"));
    }

    @Test
    void patch_partialBio_keepsExistingDisplayName() throws Exception { // E
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Nombre original"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "bio nueva"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Nombre original"))
                .andExpect(jsonPath("$.bio").value("bio nueva"));
    }

    @Test
    void patch_blankBio_clearsItToNull() throws Exception { // F
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "bio a borrar"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "   "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist());

        User reloaded = userRepository.findByUsername("facu").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getBio()).isNull();
    }

    @Test
    void patch_blankDisplayName_isRejected() throws Exception { // G
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("displayName cannot be blank"));

        User reloaded = userRepository.findByUsername("facu").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getDisplayName()).isEqualTo("Facu");
    }

    @Test
    void patch_displayNameTooLong_isRejected() throws Exception { // H
        String body = "{\"displayName\": \"" + "a".repeat(101) + "\"}";

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_bioTooLong_isRejected() throws Exception { // I
        String body = "{\"bio\": \"" + "a".repeat(501) + "\"}";

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_profileVisibility_stillUpdatable() throws Exception { // J
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Facundo R", "profileVisibility": "PRIVATE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Facundo R"))
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"));
    }

    @Test
    void patch_doesNotLoseAvatar_whenUpdatingOnlyDisplayName() throws Exception { // K
        setAvatar("{\"avatarId\": \"luna\"}").andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Facundo R"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarId").value("luna"));
    }

    @Test
    void patch_response_reflectsPersistedValues_andTrimsWhitespace() throws Exception { // L
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "  Facundo R  ", "bio": "  con espacios  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Facundo R"))
                .andExpect(jsonPath("$.bio").value("con espacios"));
    }

    @Test
    void patch_changesArePersisted_onReloadOfMe() throws Exception { // M
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Facundo R", "bio": "bio persistida"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Facundo R"))
                .andExpect(jsonPath("$.bio").value("bio persistida"));
    }

    @Test
    void shouldReturnUnauthorized_whenUpdatingProfileWithoutToken() throws Exception {
        String updateBody = """
                {
                    "bio": "intentando sin token"
                }
                """;

        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnPublicProfile_ofAnotherUser() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", UserRole.USER);

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("soumia"))
                .andExpect(jsonPath("$.followedByCurrentUser").value(false))
                .andExpect(jsonPath("$.followersCount").value(0));
    }

    @Test
    void shouldReturnNotFound_whenViewingNonexistentProfile() throws Exception {
        mockMvc.perform(get("/api/users/{userId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldExcludeSelfAndFollowedUsers_fromDiscoverList() throws Exception {
        User followed = registerUser("soumia", "soumia@example.com", UserRole.USER);
        User notFollowed = registerUser("otro", "otro@example.com", UserRole.USER);

        User me = userRepository.findByUsername("facu").orElseThrow();
        followRepository.save(Follow.builder().follower(me).following(followed).build());

        mockMvc.perform(get("/api/users/discover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("otro"));
    }

    // ============================================================
    // AVATARES ILUSTRADOS (sin fotos)
    // ============================================================

    @Test
    void choosesAnAvatarFromTheCatalog_andCanGoBackToInitials() throws Exception {
        setAvatar("{\"avatarId\": \"hoja\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarId").value("hoja"));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.avatarId").value("hoja"));

        setAvatar("{\"avatarId\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarId").doesNotExist());
    }

    @Test
    void rejectsAnythingOutsideTheCatalog_includingUrls() throws Exception {
        setAvatar("{\"avatarId\": \"dragon\"}").andExpect(status().isBadRequest());
        setAvatar("{\"avatarId\": \"https://example.com/me.png\"}").andExpect(status().isBadRequest());
        setAvatar("{\"avatarId\": \"HOJA\"}").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.avatarId").doesNotExist());
    }

    @Test
    void photosCanNoLongerBeUploaded() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", "fake-image-bytes".getBytes()
        );
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void choosingAnAvatarRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/users/me/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarId\": \"sol\"}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions setAvatar(String body) throws Exception {
        return mockMvc.perform(put("/api/users/me/avatar")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
