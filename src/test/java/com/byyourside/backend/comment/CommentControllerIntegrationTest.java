package com.byyourside.backend.comment;

import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.post.PostVisibility;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CommentControllerIntegrationTest {

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
    private CommentRepository commentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User facu;
    private Post post;
    private String facuToken;

    @BeforeEach
    void setUp() throws Exception {
        commentRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");

        post = postRepository.save(Post.builder()
                .author(facu)
                .content("Hoy fue un dia dificil, pero aca estoy.")
                .visibility(PostVisibility.PUBLIC)
                .build());
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
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    private record LoginPayload(String username, String password) {
    }

    @Test
    void shouldCreateComment_whenAuthenticated() throws Exception {
        String body = """
                {"content": "Gracias por compartir esto"}
                """;

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Gracias por compartir esto"))
                .andExpect(jsonPath("$.postId").value(post.getId().toString()))
                .andExpect(jsonPath("$.author.username").value("facu"));
    }

    @Test
    void shouldReturnBadRequest_whenContentIsBlank() throws Exception {
        String body = """
                {"content": ""}
                """;

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.content").exists());
    }

    @Test
    void shouldReturnNotFound_whenPostDoesNotExist() throws Exception {
        String body = """
                {"content": "comentario a un post inexistente"}
                """;

        mockMvc.perform(post("/api/posts/{postId}/comments", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnUnauthorized_whenCreatingCommentWithoutToken() throws Exception {
        String body = """
                {"content": "intentando sin token"}
                """;

        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldListCommentsInChronologicalOrder() throws Exception {
        createComment("primer comentario");
        createComment("segundo comentario");

        mockMvc.perform(get("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("primer comentario"))
                .andExpect(jsonPath("$[1].content").value("segundo comentario"));
    }

    @Test
    void shouldReturnNotFound_whenListingCommentsOfNonexistentPost() throws Exception {
        mockMvc.perform(get("/api/posts/{postId}/comments", UUID.randomUUID())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldExcludeRemovedComments_fromListing() throws Exception {
        Comment visible = createComment("comentario visible");
        Comment removed = createComment("comentario moderado");
        removed.setStatus(CommentStatus.REMOVED);
        commentRepository.save(removed);

        mockMvc.perform(get("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("comentario visible"));
    }

    private Comment createComment(String content) {
        return commentRepository.save(Comment.builder()
                .post(post)
                .author(facu)
                .content(content)
                .build());
    }
}