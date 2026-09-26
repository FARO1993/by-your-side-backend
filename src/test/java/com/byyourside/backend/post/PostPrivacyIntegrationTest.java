package com.byyourside.backend.post;

import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.support.PostSupportRepository;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Fase 9.1/9.2: interaccion entre privacidad de perfil (ProfileVisibility) y
// privacidad de publicaciones (PostVisibility, ya existente de una fase
// anterior). PostVisibility en si (create/update/detalle basico) ya tenia
// cobertura en PostControllerIntegrationTest -- este archivo cubre
// puntualmente lo nuevo de esta fase: que un perfil PRIVATE oculte sus posts
// a terceros sin importar PostVisibility, el feed, y las rutas indirectas
// (comments/support).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PostPrivacyIntegrationTest {

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
    private PostRepository postRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private PostSupportRepository postSupportRepository;

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
        commentRepository.deleteAll();
        postSupportRepository.deleteAll();
        postRepository.deleteAll();
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

    private Post savePost(User author, String content, PostVisibility visibility) {
        return postRepository.save(Post.builder()
                .author(author)
                .content(content)
                .visibility(visibility)
                .build());
    }

    // ============================================================
    // POST DETAIL: perfil privado domina sobre PostVisibility (S, T, Q)
    // ============================================================

    @Test
    void shouldReturnOwnPrivatePost_evenWhenOwnProfileIsPrivate() throws Exception { // T
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);
        Post ownPrivate = savePost(mainUser, "mi post privado", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/posts/{postId}", ownPrivate.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("mi post privado"));
    }

    @Test
    void shouldHidePublicPost_whenAuthorProfileIsPrivate_forThirdParty() throws Exception { // S
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        Post publicPost = savePost(other, "post publico de perfil privado", PostVisibility.PUBLIC);

        mockMvc.perform(get("/api/posts/{postId}", publicPost.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldShowPublicPost_toAcceptedFollower_whenAuthorProfileIsPrivate() throws Exception {
        // Fase 9.3: esta fila en `follows` representa una relacion YA
        // ACEPTADA (nunca existe una fila real para una FollowRequest
        // todavia PENDING) -- un follower efectivo de un perfil PRIVATE SI
        // ve sus posts PUBLIC/FOLLOWERS_ONLY. Esto reemplaza el
        // comportamiento de Fase 9.1/9.2, donde el perfil PRIVATE bloqueaba
        // a cualquier tercero sin excepcion (ver
        // docs/BACKEND_ARCHITECTURE.md § Follow requests).
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());
        Post publicPost = savePost(other, "post publico de perfil privado", PostVisibility.PUBLIC);

        mockMvc.perform(get("/api/posts/{postId}", publicPost.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("post publico de perfil privado"));
    }

    @Test
    void shouldHidePrivatePost_evenFromAcceptedFollower_whenAuthorProfileIsPrivate() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());
        Post privatePost = savePost(other, "post privado de perfil privado", PostVisibility.PRIVATE);

        // PRIVATE a nivel de post sigue siendo "solo el autor", sin importar
        // que tan aceptado sea el follower.
        mockMvc.perform(get("/api/posts/{postId}", privatePost.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldHideFollowersOnlyPost_whenViewerDoesNotFollowAuthor() throws Exception { // Q
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        Post followersOnly = savePost(other, "solo para seguidores", PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(get("/api/posts/{postId}", followersOnly.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // POSTS POR USUARIO: perfil privado oculta el listado completo
    // ============================================================

    @Test
    void shouldReturnEmptyList_notNotFound_whenTargetProfileIsPrivate() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        savePost(other, "post publico de perfil privado", PostVisibility.PUBLIC);

        mockMvc.perform(get("/api/users/{userId}/posts", other.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void shouldShowAllOwnPosts_whenOwnProfileIsPrivate() throws Exception {
        mainUser.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(mainUser);
        savePost(mainUser, "publico", PostVisibility.PUBLIC);
        savePost(mainUser, "solo seguidores", PostVisibility.FOLLOWERS_ONLY);
        savePost(mainUser, "privado", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/users/{userId}/posts", mainUser.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    // ============================================================
    // FEED (U, V, W, X, Y, AA)
    // ============================================================

    @Test
    void feedShouldIncludeOwnPrivatePosts() throws Exception { // U
        savePost(mainUser, "mi post privado", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content").value("mi post privado"));
    }

    @Test
    void feedShouldExcludePrivatePosts_ofOthers_evenIfFollowed() throws Exception { // V
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());
        savePost(other, "post privado ajeno", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feedShouldIncludeFollowersOnlyPosts_whenFollowingAuthor() throws Exception { // W
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());
        savePost(other, "solo para seguidores", PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void feedShouldExcludeFollowersOnlyPosts_whenNotFollowingAuthor() throws Exception { // X
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        savePost(other, "solo para seguidores", PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void feedShouldIncludePublicAndFollowersOnlyPosts_ofPrivateProfileAuthor_whenAcceptedFollower() throws Exception { // Z
        // Fase 9.3: reemplaza el comportamiento de Fase 9.1/9.2 (ver
        // shouldShowPublicPost_toAcceptedFollower_whenAuthorProfileIsPrivate
        // en PostPrivacyIntegrationTest para el caso de detalle de post).
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());
        savePost(other, "publico de perfil privado", PostVisibility.PUBLIC);
        savePost(other, "seguidores de perfil privado", PostVisibility.FOLLOWERS_ONLY);
        savePost(other, "privado de perfil privado", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2)); // PRIVATE sigue excluido
    }

    @Test
    void feedShouldPreserveNewestFirstOrder_afterFiltering() throws Exception { // AA
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        followRepository.save(Follow.builder().follower(mainUser).following(other).build());

        savePost(mainUser, "primero", PostVisibility.PUBLIC);
        Thread.sleep(10);
        savePost(other, "segundo", PostVisibility.PUBLIC);
        Thread.sleep(10);
        savePost(mainUser, "tercero", PostVisibility.PRIVATE);

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].content").value("tercero"))
                .andExpect(jsonPath("$.content[1].content").value("segundo"))
                .andExpect(jsonPath("$.content[2].content").value("primero"));
    }

    // ============================================================
    // ACCESO INDIRECTO: comments/support no deben revelar posts invisibles
    // ============================================================

    @Test
    void shouldNotListComments_ofInvisiblePost() throws Exception { // AB
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        Post hidden = savePost(other, "post oculto", PostVisibility.PUBLIC);

        mockMvc.perform(get("/api/posts/{postId}/comments", hidden.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldNotAllowCommenting_onInvisiblePost() throws Exception { // AC
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        Post hidden = savePost(other, "post oculto", PostVisibility.PUBLIC);

        mockMvc.perform(post("/api/posts/{postId}/comments", hidden.getId())
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "intento comentar algo que no deberia ver"}
                                """))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(commentRepository.count()).isZero();
    }

    @Test
    void shouldNotAllowCommenting_onFollowersOnlyPost_whenNotFollowing() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        Post followersOnly = savePost(other, "solo seguidores", PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(post("/api/posts/{postId}/comments", followersOnly.getId())
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "no soy seguidor"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldNotAllowSupporting_onInvisiblePost() throws Exception { // AD
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PRIVATE);
        Post hidden = savePost(other, "post oculto", PostVisibility.PUBLIC);

        mockMvc.perform(post("/api/posts/{postId}/support", hidden.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(postSupportRepository.count()).isZero();
    }

    @Test
    void shouldNotAllowSupporting_onFollowersOnlyPost_whenNotFollowing() throws Exception {
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        Post followersOnly = savePost(other, "solo seguidores", PostVisibility.FOLLOWERS_ONLY);

        mockMvc.perform(post("/api/posts/{postId}/support", followersOnly.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldAllowCommentingAndSupporting_onVisiblePublicPost() throws Exception { // AE (regresion positiva)
        User other = registerUser("soumia", "soumia@example.com", ProfileVisibility.PUBLIC);
        Post visible = savePost(other, "post visible", PostVisibility.PUBLIC);

        mockMvc.perform(post("/api/posts/{postId}/comments", visible.getId())
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "puedo comentar esto"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/posts/{postId}/support", visible.getId())
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isCreated());
    }
}
