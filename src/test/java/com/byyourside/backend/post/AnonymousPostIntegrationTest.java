package com.byyourside.backend.post;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.comment.CommentRepository;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.mute.UserMute;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.notification.NotificationRepository;
import com.byyourside.backend.postresponse.PostResponseRepository;
import com.byyourside.backend.report.ReportRepository;
import com.byyourside.backend.user.ProfileVisibility;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import com.byyourside.backend.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Posts anonimos (V20). Cada test cubre una forma de des-anonimizar un post:
// el autor nunca se expone a terceros (ni via `author`, ni via
// `followedByCurrentUser`, ni via feed de seguidores, perfil, comentarios o
// la respuesta a quien reporta), salvo a moderacion en la cola de reportes.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AnonymousPostIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private PostRepository postRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private PostResponseRepository postResponseRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private FollowRepository followRepository;
    @Autowired private FollowRequestRepository followRequestRepository;
    @Autowired private UserBlockRepository userBlockRepository;
    @Autowired private UserMuteRepository userMuteRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private User author;
    private String authorToken;
    private User reader;
    private String readerToken;
    private String moderatorToken;

    @BeforeEach
    void setUp() throws Exception {
        reportRepository.deleteAll();
        notificationRepository.deleteAll();
        commentRepository.deleteAll();
        postResponseRepository.deleteAll();
        postRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        author = registerUser("autora", UserRole.USER, ProfileVisibility.PUBLIC);
        authorToken = login("autora");
        reader = registerUser("lectora", UserRole.USER, ProfileVisibility.PUBLIC);
        readerToken = login("lectora");
        registerUser("moderadora", UserRole.MODERATOR, ProfileVisibility.PUBLIC);
        moderatorToken = login("moderadora");
    }

    private User registerUser(String username, UserRole role, ProfileVisibility visibility) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(role)
                .status(UserStatus.ACTIVE)
                .profileVisibility(visibility)
                .build());
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

    private UUID createAnonymousPost(String token, String content) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnonymousPayload(content, true))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private record AnonymousPayload(String content, boolean anonymous) {
    }

    // --- crear ---

    @Test
    void shouldCreateAnonymousPost_andShowTheAuthorOnlyToThemselves() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "algo que no me animo a firmar", "anonymous": true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anonymous").value(true))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.author.username").value("autora"));
    }

    @Test
    void shouldCreateNonAnonymousPost_byDefault() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "con mi nombre"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anonymous").value(false))
                .andExpect(jsonPath("$.author.username").value("autora"));
    }

    @Test
    void shouldRejectAnonymousPost_thatIsNotPublic() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "solo seguidores", "anonymous": true, "visibility": "FOLLOWERS_ONLY"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotAllowMakingAnAnonymousPostNonPublic_onUpdate() throws Exception {
        UUID postId = createAnonymousPost(authorToken, "anonimo");

        mockMvc.perform(patch("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility": "PRIVATE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldLimitAnonymousPostsToThreePerDay_countingDeletedOnes() throws Exception {
        UUID first = createAnonymousPost(authorToken, "uno");
        createAnonymousPost(authorToken, "dos");
        mockMvc.perform(delete("/api/posts/{postId}", first)
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().is2xxSuccessful());
        createAnonymousPost(authorToken, "tres");

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnonymousPayload("cuatro", true))))
                .andExpect(status().isTooManyRequests());

        // Publicar con nombre sigue funcionando.
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "con nombre"}
                                """))
                .andExpect(status().isCreated());
    }

    // --- el autor nunca se expone a terceros ---

    @Test
    void shouldHideAuthorAndFollowRelation_inAnonymousSpace_evenForFollowers() throws Exception {
        followRepository.save(Follow.builder().follower(reader).following(author).build());
        UUID postId = createAnonymousPost(authorToken, "hoy no puedo mas");

        String body = mockMvc.perform(get("/api/posts/anonymous")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(postId.toString()))
                .andExpect(jsonPath("$.content[0].anonymous").value(true))
                .andExpect(jsonPath("$.content[0].author").doesNotExist())
                .andExpect(jsonPath("$.content[0].followedByCurrentUser").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(author.getId().toString()).doesNotContain("autora");
    }

    @Test
    void shouldHideAuthor_whenOpeningTheAnonymousPostDirectly() throws Exception {
        UUID postId = createAnonymousPost(authorToken, "anonimo");

        String body = mockMvc.perform(get("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(author.getId().toString());

        mockMvc.perform(get("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author.username").value("autora"));
    }

    @Test
    void shouldNotShowAnonymousPosts_inFollowersFeed_butKeepThemInTheAuthorsOwnFeed() throws Exception {
        followRepository.save(Follow.builder().follower(reader).following(author).build());
        createAnonymousPost(authorToken, "anonimo");

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/posts/feed")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].anonymous").value(true));
    }

    @Test
    void shouldNotShowAnonymousPosts_onTheAuthorsProfile_exceptForTheAuthor() throws Exception {
        createAnonymousPost(authorToken, "anonimo");

        mockMvc.perform(get("/api/users/{userId}/posts", author.getId())
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/users/{userId}/posts", author.getId())
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void shouldShowAnonymousPosts_evenIfTheAuthorsProfileIsPrivate() throws Exception {
        author.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(author);
        UUID postId = createAnonymousPost(authorToken, "anonimo");

        mockMvc.perform(get("/api/posts/anonymous")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
    }

    // --- bloqueo y silencio con el autor real ---

    @Test
    void shouldExcludeAnonymousPosts_fromBlockedAuthors_inBothDirections() throws Exception {
        UUID postId = createAnonymousPost(authorToken, "anonimo");
        userBlockRepository.save(UserBlock.builder().blocker(author).blocked(reader).build());

        mockMvc.perform(get("/api/posts/anonymous")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(get("/api/posts/{postId}", postId)
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldExcludeAnonymousPosts_fromMutedAuthors() throws Exception {
        createAnonymousPost(authorToken, "anonimo");
        userMuteRepository.save(UserMute.builder().muter(reader).muted(author).build());

        mockMvc.perform(get("/api/posts/anonymous")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // --- respuestas, comentarios y moderacion ---

    @Test
    void shouldAllowPresenceResponses_butNotComments() throws Exception {
        UUID postId = createAnonymousPost(authorToken, "anonimo");

        mockMvc.perform(put("/api/posts/{postId}/response", postId)
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "WITH_YOU"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "te leo"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/posts/{postId}/comments", postId)
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldShowTheRealAuthor_onlyToModeration_neverToTheReporter() throws Exception {
        UUID postId = createAnonymousPost(authorToken, "anonimo");

        String reportBody = mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType": "POST", "targetId": "%s", "reason": "SELF_HARM_RISK"}
                                """.formatted(postId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetAuthorId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(reportBody).doesNotContain(author.getId().toString());

        String queue = mockMvc.perform(get("/api/reports/queue")
                        .header("Authorization", "Bearer " + moderatorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(queue).get("content").get(0);
        assertThat(first.get("targetId").asText()).isEqualTo(postId.toString());
        assertThat(first.get("targetAuthorId").asText()).isEqualTo(author.getId().toString());

        // Quien reporta no puede ver la cola.
        mockMvc.perform(get("/api/reports/queue")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }
}
