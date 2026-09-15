package com.byyourside.backend.post;

import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @BeforeEach
    void setUp() throws Exception {
        postRepository.deleteAll();
        followRepository.deleteAll();
        userRepository.deleteAll();

        mainUser = registerUser("facu", "facu@example.com");
        mainUserToken = login("facu", "secretpass123");
    }

    private User registerUser(String username, String email) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(com.byyourside.backend.user.UserRole.USER)
                .status(com.byyourside.backend.user.UserStatus.ACTIVE)
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
        String body = """
                {"content": "mi propio post"}
                """;
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + mainUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + mainUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("mi propio post"));
    }

    @Test
    void shouldIncludeFollowedUsersPostsInFeed() throws Exception {
        User otherUser = registerUser("soumia", "soumia@example.com");
        String otherUserToken = login("soumia", "secretpass123");

        // facu sigue a soumia
        followRepository.save(Follow.builder()
                .follower(mainUser)
                .following(otherUser)
                .build());

        String body = """
                {"content": "post de soumia"}
                """;
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

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

        String body = """
                {"content": "post de alguien que no seguis"}
                """;
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

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
}