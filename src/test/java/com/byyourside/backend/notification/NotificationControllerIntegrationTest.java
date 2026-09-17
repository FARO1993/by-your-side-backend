package com.byyourside.backend.notification;

import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
import com.byyourside.backend.support.PostSupportRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class NotificationControllerIntegrationTest {

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
    private PostSupportRepository postSupportRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CommentRepository commentRepository;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
        commentRepository.deleteAll();
        postSupportRepository.deleteAll();
        postRepository.deleteAll();
        followRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload(username, "secretpass123"));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private record LoginPayload(String username, String password) {
    }

    @Test
    void shouldCreateNotification_whenSomeoneFollowsYou() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_FOLLOWER"))
                .andExpect(jsonPath("$.content[0].actor.username").value("soumia"))
                .andExpect(jsonPath("$.content[0].read").value(false));
    }

    @Test
    void shouldCreateNotification_whenSomeoneCommentsYourPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "que fuerte tu post"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_COMMENT"))
                .andExpect(jsonPath("$.content[0].postId").value(post.getId().toString()));
    }

    @Test
    void shouldNotCreateNotification_whenCommentingYourOwnPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "comentando mi propio post"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void shouldCreateNotification_whenSomeoneSupportsYourPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .author(facu).content("mi post").visibility(PostVisibility.PUBLIC).build());

        mockMvc.perform(post("/api/posts/{postId}/support", post.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("NEW_SUPPORT"));
    }

    @Test
    void shouldReturnUnreadCount() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void shouldMarkAllAsRead() throws Exception {
        mockMvc.perform(post("/api/follows/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void shouldReturnUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }
}