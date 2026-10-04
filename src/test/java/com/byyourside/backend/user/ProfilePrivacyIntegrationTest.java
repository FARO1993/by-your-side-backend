package com.byyourside.backend.user;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Fase 9.1: privacidad de perfil (PUBLIC/PRIVATE). Cubre exclusivamente el
// perfil en si -- la interaccion perfil/post esta en PostPrivacyIntegrationTest.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ProfilePrivacyIntegrationTest {

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
    private FollowRepository followRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User mainUser;
    private String mainUserToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        mainUser = registerUser("facu", "facu@example.com", ProfileVisibility.PUBLIC);
        mainUserToken = login("facu", "secretpass123");
    }

    private User registerUser(String username, String email, ProfileVisibility visibility) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .bio("bio de " + username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .profileVisibility(visibility)
                .build();
        return userRepository.save(user);
    }

    private String login(String username, String password) throws Exception {
        String body = """
                {"email": "%s@example.com", "password": "%s"}
                """.formatted(username, password);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    // --- A: perfil PUBLIC visible ---

    @Test
    void shouldShowFullProfile_whenTargetProfileIsPublic() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("soumia"))
                .andExpect(jsonPath("$.bio").value("bio de soumia"))
                .andExpect(jsonPath("$.profileVisibility").value("PUBLIC"));
    }

    // --- B: perfil PRIVATE propio visible completo ---

    @Test
    void shouldShowOwnFullProfile_whenOwnProfileIsPrivate() throws Exception {
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("bio de facu"))
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"));
    }

    @Test
    void shouldShowOwnFullProfile_viaPublicProfileEndpoint_whenOwnProfileIsPrivate() throws Exception {
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);

        mockMvc.perform(get("/api/users/{userId}", mainUser.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("bio de facu"))
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"));
    }

    // --- C: perfil PRIVATE ajeno devuelve vista limitada ---

    @Test
    void shouldReturnLimitedProfile_notNotFound_whenTargetProfileIsPrivate() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("soumia"))
                .andExpect(jsonPath("$.displayName").value("soumia"))
                .andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"))
                .andExpect(jsonPath("$.followedByCurrentUser").value(false));
    }

    @Test
    void shouldKeepFollowerCounts_onLimitedPrivateProfile() throws Exception {
        // Ocultar contadores es una decision de producto separada, fuera de
        // esta fase -- el perfil limitado sigue exponiendolos.
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followersCount").value(1))
                .andExpect(jsonPath("$.followedByCurrentUser").value(true));
    }

    // --- D/E: cambiar visibilidad ---

    @Test
    void shouldChangeProfileVisibility_fromPublicToPrivate() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVisibility": "PRIVATE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"));

        User reloaded = userRepository.findById(mainUser.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getProfileVisibility())
                .isEqualTo(ProfileVisibility.PRIVATE);
    }

    @Test
    void shouldChangeProfileVisibility_fromPrivateBackToPublic() throws Exception {
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVisibility": "PUBLIC"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("PUBLIC"));
    }

    @Test
    void shouldNotChangeProfileVisibility_whenFieldOmitted() throws Exception {
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio": "solo actualizo la bio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVisibility").value("PRIVATE"));
    }

    // --- F: valor invalido falla ---

    @Test
    void shouldRejectInvalidProfileVisibilityValue() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVisibility": "SUPER_SECRET"}
                                """))
                .andExpect(status().isBadRequest());

        User reloaded = userRepository.findById(mainUser.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getProfileVisibility())
                .isEqualTo(ProfileVisibility.PUBLIC);
    }

    // --- G: otro usuario no puede cambiar la privacidad de otro ---

    @Test
    void shouldOnlyChangeOwnProfileVisibility_neverAnotherUsers() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);

        // El body no tiene (ni puede tener) un identificador de otro usuario --
        // PATCH /me siempre opera sobre el propio JWT.
        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVisibility": "PRIVATE"}
                                """))
                .andExpect(status().isOk());

        User reloadedOther = userRepository.findById(other.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloadedOther.getProfileVisibility())
                .isEqualTo(ProfileVisibility.PUBLIC);
    }

    // --- H/I: perfil PRIVATE ahora requiere aceptacion (Fase 9.3) ---
    //
    // Reemplaza el comportamiento de Fase 9.1/9.2 (follow siempre inmediato,
    // sin excepcion) -- el core de follow requests (crear, listar, aceptar,
    // rechazar, cancelar) tiene su propia cobertura dedicada en
    // FollowRequestIntegrationTest; aca solo se cubre el efecto inmediato
    // sobre el propio perfil/GET, para no duplicar.

    @Test
    void shouldCreatePendingRequest_insteadOfImmediateFollow_whenTargetProfileIsPrivate() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);

        mockMvc.perform(post("/api/follows/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followState").value("REQUESTED"));

        // Sin aceptacion todavia: el perfil NO refleja un follow activo.
        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followedByCurrentUser").value(false))
                .andExpect(jsonPath("$.followState").value("REQUESTED"));
    }

    @Test
    void shouldFollowImmediately_whenTargetProfileIsPublic() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);

        mockMvc.perform(post("/api/follows/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followState").value("FOLLOWING"));

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followedByCurrentUser").value(true))
                .andExpect(jsonPath("$.followState").value("FOLLOWING"));
    }

    @Test
    void shouldUnfollow_evenWhenTargetProfileIsPrivate() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());

        mockMvc.perform(delete("/api/follows/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNoContent());
    }

    // --- Backend Debt B2: consistencia Profile/Follow que no tenia cobertura dedicada ---

    @Test
    void shouldShowFullProfile_toAcceptedFollower_ofPrivateProfile() throws Exception { // item 28-Q
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("bio de soumia"))
                .andExpect(jsonPath("$.followState").value("FOLLOWING"));
    }

    @Test
    void pendingFollowRequest_doesNotInflateFollowerOrFollowingCounts() throws Exception { // item 20/28-W
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);

        mockMvc.perform(post("/api/follows/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followState").value("REQUESTED"));

        mockMvc.perform(get("/api/users/{userId}", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followersCount").value(0))
                .andExpect(jsonPath("$.followState").value("REQUESTED"));

        mockMvc.perform(get("/api/users/{userId}", mainUser.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(jsonPath("$.followingCount").value(0));
    }

    // --- Discover: bypass lateral tapado ---

    @Test
    void shouldHideBio_inDiscoverList_whenTargetProfileIsPrivate() throws Exception {
        registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);

        mockMvc.perform(get("/api/users/discover")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("soumia"))
                .andExpect(jsonPath("$.content[0].bio").doesNotExist())
                .andExpect(jsonPath("$.content[0].profileVisibility").value("PRIVATE"));
    }
}
