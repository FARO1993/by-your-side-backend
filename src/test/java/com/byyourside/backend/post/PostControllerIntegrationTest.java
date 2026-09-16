package com.byyourside.backend.post;

import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
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
class PostControllerIntegrationTest {

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
    private PostRepository postRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User mainUser;
    private String mainUserToken;
    private String moderatorToken;

    @BeforeEach
    void setUp() throws Exception {
        postRepository.deleteAll();
        followRepository.deleteAll();
        userRepository.deleteAll();

        mainUser = registerUser("facu", "facu@example.com", UserRole.USER);
        mainUserToken = login("facu", "secretpass123");

        registerUser("moderator", "moderator@example.com", UserRole.MODERATOR);
        moderatorToken = login("moderator", "secretpass123");
    }

    private User registerUser(String username, String email) {
        return registerUser(username, email, UserRole.USER);
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

    private String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload(username, password));

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

    private UUID createPost(String token, String content) throws Exception {
        String body = objectMapper.writeValueAsString(new ContentPayload(content));

        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private record ContentPayload(String content) {
    }

    // --- tests existentes (create, feed) ---

    @Test
    void shouldCreatePost_whenAuthenticated() throws Exception {
        String body = """
                {"content": "Hoy fue un dia dificil, pero aca estoy."}
                """;

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Hoy fue un dia dificil, pero aca estoy."))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.author.username").value("facu"));
    }

    @Test
    void shouldReturnBadRequest_whenContentIsBlank() throws Exception {
        String body = """
                {"content": ""}
                """;

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.content").exists());
    }

    @Test
    void shouldReturnUnauthorized_whenCreatingPostWithoutToken() throws Exception {
        String body = """
                {"content": "intentando sin token"}
                """;

        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldIncludeOwnPostsInFeed() throws Exception {
        createPost(mainUserToken, "mi propio post");

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("mi propio post"));
    }

    @Test
    void shouldIncludeFollowedUsersPostsInFeed() throws Exception {
        User otherUser = registerUser("soumia", "soumia@example.com");
        String otherUserToken = login("soumia", "secretpass123");

        followRepository.save(Follow.builder()
                .follower(mainUser)
                .following(otherUser)
                .build());

        createPost(otherUserToken, "post de soumia");

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("post de soumia"))
                .andExpect(jsonPath("$.content[0].author.username").value("soumia"));
    }

    @Test
    void shouldNotIncludeNonFollowedUsersPostsInFeed() throws Exception {
        String otherUserToken = login(
                registerUser("desconocido", "desconocido@example.com").getUsername(),
                "secretpass123"
        );

        createPost(otherUserToken, "post de alguien que no seguis");

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void shouldReturnUnauthorized_whenGettingFeedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/posts/feed"))
                .andExpect(status().isUnauthorized());
    }

    // --- tests nuevos: update / delete ---

    @Test
    void shouldUpdatePost_whenAuthenticatedAsAuthor() throws Exception {
        UUID postId = createPost(mainUserToken, "contenido original");

        mockMvc.perform(patch("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "contenido editado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("contenido editado"));
    }

    @Test
    void shouldReturnForbidden_whenUpdatingSomeoneElsesPost() throws Exception {
        String otherUserToken = login(
                registerUser("otro", "otro@example.com").getUsername(),
                "secretpass123"
        );
        UUID postId = createPost(otherUserToken, "post de otro usuario");

        mockMvc.perform(patch("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "intento editar algo que no es mio"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnNotFound_whenUpdatingNonexistentPost() throws Exception {
        mockMvc.perform(patch("/api/posts/{postId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "no importa"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldDeletePost_whenAuthenticatedAsAuthor() throws Exception {
        UUID postId = createPost(mainUserToken, "post a borrar");

        mockMvc.perform(delete("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNoContent());

        Post deleted = postRepository.findById(postId).orElseThrow();
        assert deleted.getStatus() == PostStatus.REMOVED;
    }

    @Test
    void shouldExcludeDeletedPost_fromFeed() throws Exception {
        UUID postId = createPost(mainUserToken, "post a borrar");

        mockMvc.perform(delete("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void shouldAllowModeratorToDeleteAnyPost() throws Exception {
        UUID postId = createPost(mainUserToken, "post ajeno al moderador");

        mockMvc.perform(delete("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + moderatorToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturnForbidden_whenNonAuthorNonModeratorDeletesPost() throws Exception {
        String otherUserToken = login(
                registerUser("otro", "otro@example.com").getUsername(),
                "secretpass123"
        );

        mockMvc.perform(delete("/api/posts/{postId}", createPost(mainUserToken, "post protegido"))
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden());
    }
}