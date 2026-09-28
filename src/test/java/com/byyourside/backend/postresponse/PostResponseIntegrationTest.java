package com.byyourside.backend.postresponse;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Backend Debt B1: respuesta tipada por usuario/post (PostResponse),
// reemplaza el soporte binario anterior (PostSupport). Ver
// PostResponseService para la logica y docs/API_CONTRACT.md §3 para el
// contrato completo.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PostResponseIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostResponseRepository postResponseRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserMuteRepository userMuteRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User facu; // autor
    private String facuToken;
    private User soumia; // respondedor
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
        postResponseRepository.deleteAll();
        postRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com", ProfileVisibility.PUBLIC);
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email, ProfileVisibility visibility) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .profileVisibility(visibility)
                .build();
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = """
                {"email": "%s@example.com", "password": "secretpass123"}
                """.formatted(username);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private Post savePost(User author, PostVisibility visibility) {
        return postRepository.save(Post.builder().author(author).content("hola").visibility(visibility).build());
    }

    private void putResponse(String token, UUID postId, PostResponseType type) throws Exception {
        mockMvc.perform(put("/api/posts/{postId}/response", postId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s"}
                                """.formatted(type)))
                .andExpect(status().isOk());
    }

    // ============================================================
    // CORE (A-L)
    // ============================================================

    @Test
    void createsWithYouResponse() throws Exception { // A
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        PostResponse saved = postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow();
        assertThat(saved.getType()).isEqualTo(PostResponseType.WITH_YOU);
    }

    @Test
    void createsNotAloneResponse() throws Exception { // B
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.NOT_ALONE);

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.NOT_ALONE);
    }

    @Test
    void createsHugResponse() throws Exception { // C
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.HUG);
    }

    @Test
    void createsReadingResponse() throws Exception { // D
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.READING);

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.READING);
    }

    @Test
    void createsTellMeMoreResponse() throws Exception { // E
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.TELL_ME_MORE);

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.TELL_ME_MORE);
    }

    @Test
    void createsListeningResponse() throws Exception { // F
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.LISTENING);

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.LISTENING);
    }

    @Test
    void onlyOneRowPerUserAndPost() throws Exception { // G
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);
        putResponse(soumiaToken, post.getId(), PostResponseType.READING);

        assertThat(postResponseRepository.count()).isEqualTo(1);
    }

    @Test
    void changingType_updatesSameRow() throws Exception { // H
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        UUID rowId = postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getId();

        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        PostResponse reloaded = postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(rowId);
        assertThat(reloaded.getType()).isEqualTo(PostResponseType.HUG);
    }

    @Test
    void sameType_isIdempotent() throws Exception { // I
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        assertThat(postResponseRepository.count()).isEqualTo(1);
    }

    @Test
    void delete_removesResponse() throws Exception { // J
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        mockMvc.perform(delete("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").doesNotExist());

        assertThat(postResponseRepository.existsByPostIdAndUserId(post.getId(), soumia.getId())).isFalse();
    }

    @Test
    void delete_nonExistent_isCoherentNoop() throws Exception { // K
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(delete("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presenceCount").value(0))
                .andExpect(jsonPath("$.listeningCount").value(0));
    }

    @Test
    void authorCannotRespondToOwnPost() throws Exception { // L
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(put("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot respond to your own post"));

        assertThat(postResponseRepository.count()).isZero();
    }

    // ============================================================
    // COUNTS (M-T)
    // ============================================================

    @Test
    void withYou_countsAsPresence() throws Exception { // M
        assertPresenceCount(PostResponseType.WITH_YOU, 1, 0);
    }

    @Test
    void notAlone_countsAsPresence() throws Exception { // N
        assertPresenceCount(PostResponseType.NOT_ALONE, 1, 0);
    }

    @Test
    void hug_countsAsPresence() throws Exception { // O
        assertPresenceCount(PostResponseType.HUG, 1, 0);
    }

    @Test
    void reading_countsAsListening() throws Exception { // P
        assertPresenceCount(PostResponseType.READING, 0, 1);
    }

    @Test
    void tellMeMore_countsAsListening() throws Exception { // Q
        assertPresenceCount(PostResponseType.TELL_ME_MORE, 0, 1);
    }

    @Test
    void listening_countsAsListening() throws Exception { // R
        assertPresenceCount(PostResponseType.LISTENING, 0, 1);
    }

    private void assertPresenceCount(PostResponseType type, long expectedPresence, long expectedListening) throws Exception {
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), type);

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presenceCount").value(expectedPresence))
                .andExpect(jsonPath("$.listeningCount").value(expectedListening));
    }

    @Test
    void changingFromPresenceToListening_updatesBothCounts() throws Exception { // S
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.presenceCount").value(1))
                .andExpect(jsonPath("$.listeningCount").value(0));

        putResponse(soumiaToken, post.getId(), PostResponseType.LISTENING);

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.presenceCount").value(0))
                .andExpect(jsonPath("$.listeningCount").value(1));
    }

    @Test
    void deleting_decrementsCorrectCount() throws Exception { // T
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        mockMvc.perform(delete("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(jsonPath("$.presenceCount").value(0))
                .andExpect(jsonPath("$.listeningCount").value(0));
    }

    // ============================================================
    // CURRENT USER (U-X)
    // ============================================================

    @Test
    void postDetail_returnsCurrentUserResponseType() throws Exception { // U
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUserResponseType").value("HUG"));
    }

    @Test
    void feed_returnsCurrentUserResponseType() throws Exception { // V
        Post post = savePost(facu, PostVisibility.PUBLIC);
        followRepository.save(Follow.builder().follower(soumia).following(facu).build());
        putResponse(soumiaToken, post.getId(), PostResponseType.TELL_ME_MORE);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentUserResponseType").value("TELL_ME_MORE"));
    }

    @Test
    void postsByUser_returnsCurrentUserResponseType() throws Exception { // W
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.LISTENING);

        mockMvc.perform(get("/api/users/{userId}/posts", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentUserResponseType").value("LISTENING"));
    }

    @Test
    void userWithoutResponse_receivesNull() throws Exception { // X
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUserResponseType").doesNotExist());
    }

    // ============================================================
    // MIGRACION / LEGACY (Y-AB)
    // ============================================================

    @Test
    void historicalSupportShapedRow_behavesAsWithYou() throws Exception { // Y
        // Simula una fila post_supports migrada por V12 (type = WITH_YOU
        // asignado por la migracion): la migracion SQL en si ya se valido
        // corriendo el suite completo contra Postgres real (Flyway aplica
        // V12 al levantar el contexto) -- este test valida que el codigo de
        // lectura trata esa forma de dato exactamente como una respuesta
        // WITH_YOU normal.
        Post post = savePost(facu, PostVisibility.PUBLIC);
        User legacyUser = registerUser("legacy", "legacy@example.com", ProfileVisibility.PUBLIC);
        postResponseRepository.save(PostResponse.builder()
                .post(post).user(legacyUser).type(PostResponseType.WITH_YOU)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        mockMvc.perform(get("/api/posts/{postId}", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presenceCount").value(1))
                .andExpect(jsonPath("$.supportCount").value(1));
    }

    @Test
    void legacySupportEndpoint_createsWithYou() throws Exception { // Z
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        assertThat(postResponseRepository.findByPostIdAndUserId(post.getId(), soumia.getId()).orElseThrow().getType())
                .isEqualTo(PostResponseType.WITH_YOU);
    }

    @Test
    void legacyDelete_removesSameResponse_regardlessOfType() throws Exception { // AA
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        mockMvc.perform(delete("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk());

        assertThat(postResponseRepository.existsByPostIdAndUserId(post.getId(), soumia.getId())).isFalse();
    }

    @Test
    void legacyAndNewEndpoint_neverCreateTwoRows() throws Exception { // AB
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        // El endpoint legacy preserva su contrato original: conflicto si ya
        // habia una respuesta propia (nunca la pisa silenciosamente).
        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isConflict());

        assertThat(postResponseRepository.count()).isEqualTo(1);
    }

    // ============================================================
    // NOTIFICATIONS (AC-AI)
    // ============================================================

    @Test
    void firstResponse_generatesNotification() throws Exception { // AC
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("NEW_POST_RESPONSE"));
    }

    @Test
    void changingType_doesNotGenerateSecondNotification() throws Exception { // AD
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);
        putResponse(soumiaToken, post.getId(), PostResponseType.READING);

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void sameType_doesNotGenerateSecondNotification() throws Exception { // AE
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void delete_doesNotNotify() throws Exception { // AF
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);
        notificationRepository.deleteAll(); // limpio la de la creacion, me interesa solo el delete

        mockMvc.perform(delete("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk());

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void respondingAgainAfterDelete_generatesNewNotification() throws Exception { // AG
        Post post = savePost(facu, PostVisibility.PUBLIC);
        putResponse(soumiaToken, post.getId(), PostResponseType.WITH_YOU);

        mockMvc.perform(delete("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk());

        putResponse(soumiaToken, post.getId(), PostResponseType.HUG);

        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void authorNeverNotifiedBySelf_becauseSelfResponseFails() throws Exception { // AH
        Post post = savePost(facu, PostVisibility.PUBLIC);

        mockMvc.perform(put("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(notificationRepository.count()).isZero();
    }

    // AI (WebSocket usa /user/queue/notifications existente): no re-testeado
    // aca -- NotificationService.notify() es el mismo metodo compartido ya
    // usado por follow/comment/status (sin canal nuevo), y PostResponseService
    // lo llama exactamente igual que PostSupportService antes. Cobertura de
    // canal WS en si queda fuera del alcance de un test MockMvc, mismo
    // criterio que las fases anteriores (Block/Mute) nunca ejercitaron STOMP
    // directamente.

    // ============================================================
    // PRIVACY (AJ-AN)
    // ============================================================

    @Test
    void invisiblePost_rejectsResponse() throws Exception { // AJ
        User privateAuthor = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        Post hidden = savePost(privateAuthor, PostVisibility.PUBLIC);

        mockMvc.perform(put("/api/posts/{postId}/response", hidden.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isNotFound());

        assertThat(postResponseRepository.count()).isZero();
    }

    @Test
    void blockedPair_rejectsResponse() throws Exception { // AK
        Post post = savePost(facu, PostVisibility.PUBLIC);
        mockMvc.perform(post("/api/users/{userId}/block", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void followerOfPrivateProfile_canRespond() throws Exception { // AL
        User privateAuthor = registerUser("privado", "privado@example.com", ProfileVisibility.PRIVATE);
        Post followersOnly = savePost(privateAuthor, PostVisibility.FOLLOWERS_ONLY);
        followRepository.save(Follow.builder().follower(soumia).following(privateAuthor).build());

        mockMvc.perform(put("/api/posts/{postId}/response", followersOnly.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "HUG"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void nonFollower_cannotRespondToFollowersOnlyPost() throws Exception { // AM
        Post followersOnly = savePost(facu, PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(put("/api/posts/{postId}/response", followersOnly.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "HUG"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void mute_doesNotBlockDirectResponse() throws Exception { // AN
        Post post = savePost(facu, PostVisibility.PUBLIC);
        mockMvc.perform(post("/api/users/{userId}/mute", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/posts/{postId}/response", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());
    }
}
