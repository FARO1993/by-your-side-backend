package com.byyourside.backend.user;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.companion.CompanionOffering;
import com.byyourside.backend.companion.CompanionOfferingRepository;
import com.byyourside.backend.companion.OfferingType;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequest;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.mute.UserMute;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.status.Status;
import com.byyourside.backend.status.StatusMood;
import com.byyourside.backend.status.StatusReactionRepository;
import com.byyourside.backend.status.StatusRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Backend Debt B5.4A: `statusMood` en Discover (mood del status activo mas
// reciente, resuelto en batch, con el mismo gate de privacidad que
// GET /api/users/{id}/status).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DiscoverStatusIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StatusRepository statusRepository;
    @Autowired
    private StatusReactionRepository statusReactionRepository;
    @Autowired
    private CompanionOfferingRepository companionOfferingRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private FollowRequestRepository followRequestRepository;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User me;
    private String meToken;

    @BeforeEach
    void setUp() throws Exception {
        statusReactionRepository.deleteAll();
        statusRepository.deleteAll();
        companionOfferingRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();

        me = createUser("me", "Me Myself", ProfileVisibility.PUBLIC);
        meToken = login("me");
    }

    // ---------- helpers ----------

    private User createUser(String username, String displayName, ProfileVisibility visibility) {
        return userRepository.save(User.builder()
                .username(username).email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(displayName)
                .role(UserRole.USER).status(UserStatus.ACTIVE).profileVisibility(visibility)
                .build());
    }

    private User person(String username, String displayName) {
        return createUser(username, displayName, ProfileVisibility.PUBLIC);
    }

    private User privatePerson(String username, String displayName) {
        return createUser(username, displayName, ProfileVisibility.PRIVATE);
    }

    // createdAt lo pisa @PrePersist (Instant.now()), asi que se fija por SQL
    // para controlar el orden entre varios status del mismo usuario.
    private Status createStatus(User user, StatusMood mood, Instant createdAt, Instant expiresAt) {
        Status saved = statusRepository.save(Status.builder().user(user).mood(mood).expiresAt(expiresAt).build());
        jdbcTemplate.update("UPDATE statuses SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private Status activeStatus(User user, StatusMood mood) {
        Instant now = Instant.now();
        return createStatus(user, mood, now.minus(1, ChronoUnit.HOURS), now.plus(23, ChronoUnit.HOURS));
    }

    private void expiredStatus(User user, StatusMood mood) {
        Instant now = Instant.now();
        createStatus(user, mood, now.minus(30, ChronoUnit.HOURS), now.minus(6, ChronoUnit.HOURS));
    }

    private void activeOffering(User user) {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(user).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
    }

    private void follow(User follower, User target) {
        followRepository.save(Follow.builder().follower(follower).following(target).build());
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

    private ResultActions discover(String query) throws Exception {
        return mockMvc.perform(get(URI.create("/api/users/discover" + query))
                .header("Authorization", "Bearer " + meToken));
    }

    private JsonNode content(String query) throws Exception {
        String body = discover(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("content");
    }

    private JsonNode only(String query, String username) throws Exception {
        for (JsonNode n : content(query)) {
            if (n.get("username").asText().equals(username)) {
                return n;
            }
        }
        throw new AssertionError("user not found in discover: " + username);
    }

    // ---------- statusMood semantics ----------

    @Test
    void publicUserWithActiveStatus_showsMood() throws Exception { // 1
        activeStatus(person("pub", "Pub"), StatusMood.NEED_TO_TALK);

        assertThat(only("", "pub").get("statusMood").asText()).isEqualTo("NEED_TO_TALK");
    }

    @Test
    void publicUserWithoutStatus_moodIsNull() throws Exception { // 2
        person("none", "None");

        assertThat(only("", "none").get("statusMood").isNull()).isTrue();
    }

    @Test
    void expiredStatus_moodIsNull() throws Exception { // 3
        User user = person("old", "Old");
        expiredStatus(user, StatusMood.DIFFICULT_DAY);
        assertThat(statusRepository.count()).isEqualTo(1);

        assertThat(only("", "old").get("statusMood").isNull()).isTrue();
    }

    @Test
    void multipleActiveStatuses_usesMostRecentCreatedAt() throws Exception { // 4
        User user = person("multi", "Multi");
        Instant now = Instant.now();
        Instant expires = now.plus(20, ChronoUnit.HOURS);
        createStatus(user, StatusMood.WELL, now.minus(5, ChronoUnit.HOURS), expires);
        createStatus(user, StatusMood.HERE_FOR_SOMEONE, now.minus(1, ChronoUnit.HOURS), expires); // mas reciente
        createStatus(user, StatusMood.DIFFICULT_DAY, now.minus(3, ChronoUnit.HOURS), expires);

        assertThat(only("", "multi").get("statusMood").asText()).isEqualTo("HERE_FOR_SOMEONE");
    }

    @Test
    void expiredMoreRecentStatus_doesNotWinOverOlderActiveOne() throws Exception {
        User user = person("mix", "Mix");
        Instant now = Instant.now();
        createStatus(user, StatusMood.WELL, now.minus(10, ChronoUnit.HOURS), now.plus(10, ChronoUnit.HOURS));          // activo, viejo
        createStatus(user, StatusMood.NEED_DISTRACTION, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)); // expirado, mas nuevo

        assertThat(only("", "mix").get("statusMood").asText()).isEqualTo("WELL");
    }

    @Test
    void sameCreatedAt_isResolvedDeterministicallyById() throws Exception {
        User user = person("tie", "Tie");
        Instant now = Instant.now();
        Instant createdAt = now.minus(2, ChronoUnit.HOURS);
        Instant expires = now.plus(10, ChronoUnit.HOURS);
        createStatus(user, StatusMood.WELL, createdAt, expires);
        createStatus(user, StatusMood.NEED_TO_TALK, createdAt, expires);
        createStatus(user, StatusMood.DIFFICULT_DAY, createdAt, expires);

        // Desempate documentado: id DESC (orden de Postgres).
        String expected = jdbcTemplate.queryForObject(
                "SELECT mood FROM statuses WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, user.getId());

        assertThat(only("", "tie").get("statusMood").asText()).isEqualTo(expected);
        assertThat(only("", "tie").get("statusMood").asText()).isEqualTo(expected); // estable entre llamadas
    }

    // ---------- PRIVATE ----------

    @Test
    void privateWithoutAcceptedFollow_moodIsNull_butUserStillAppears() throws Exception { // 5, 16
        activeStatus(privatePerson("priv", "Priv"), StatusMood.NEED_TO_TALK);

        JsonNode node = only("", "priv");
        assertThat(node.get("statusMood").isNull()).isTrue();
        assertThat(node.get("profileVisibility").asText()).isEqualTo("PRIVATE");
        assertThat(node.get("bio").isNull()).isTrue();
    }

    @Test
    void privateWithPendingRequest_moodIsNull() throws Exception {
        User priv = privatePerson("pend", "Pend");
        followRequestRepository.save(FollowRequest.builder().requester(me).target(priv).build());
        activeStatus(priv, StatusMood.WELL);

        JsonNode node = only("", "pend");
        assertThat(node.get("followState").asText()).isEqualTo("REQUESTED");
        assertThat(node.get("statusMood").isNull()).isTrue();
    }

    @Test
    void privateWithFollowing_moodIsVisible_inSearch() throws Exception { // 6
        User priv = privatePerson("privfol", "Priv Followed");
        follow(me, priv);
        activeStatus(priv, StatusMood.HERE_FOR_SOMEONE);

        JsonNode node = only("?q=priv", "privfol");
        assertThat(node.get("followState").asText()).isEqualTo("FOLLOWING");
        assertThat(node.get("statusMood").asText()).isEqualTo("HERE_FOR_SOMEONE");
        assertThat(node.get("bio").isNull()).isTrue();
    }

    @Test
    void privateFollowingWithoutStatus_moodIsNull() throws Exception {
        User priv = privatePerson("privnone", "Priv None");
        follow(me, priv);

        assertThat(only("?q=privnone", "privnone").get("statusMood").isNull()).isTrue();
    }

    // ---------- block / mute ----------

    @Test
    void blockedUsersWithStatus_doNotAppear() throws Exception { // 7
        User iBlocked = person("blk", "Blocked One");
        User blockedMe = person("blkr", "Blocker One");
        activeStatus(iBlocked, StatusMood.WELL);
        activeStatus(blockedMe, StatusMood.WELL);
        userBlockRepository.save(UserBlock.builder().blocker(me).blocked(iBlocked).build());
        userBlockRepository.save(UserBlock.builder().blocker(blockedMe).blocked(me).build());

        assertThat(content("").size()).isZero();
        assertThat(content("?q=one").size()).isZero();
    }

    @Test
    void mutedUserWithStatus_doesNotAppear() throws Exception { // 8
        User muted = person("mut", "Muted One");
        activeStatus(muted, StatusMood.WELL);
        userMuteRepository.save(UserMute.builder().muter(me).muted(muted).build());

        assertThat(content("").size()).isZero();
        assertThat(content("?q=muted").size()).isZero();
    }

    // ---------- browse / search ----------

    @Test
    void browse_showsMoods() throws Exception { // 9
        activeStatus(person("b1", "Browse One"), StatusMood.WELL);
        person("b2", "Browse Two");

        Map<String, String> moods = moods("");
        assertThat(moods).containsEntry("b1", "WELL").containsEntry("b2", null);
    }

    @Test
    void search_showsMoods_includingFollowed() throws Exception { // 10
        User followed = person("s_fol", "Search Followed");
        follow(me, followed);
        activeStatus(followed, StatusMood.NEED_DISTRACTION);
        activeStatus(person("s_other", "Search Other"), StatusMood.DIFFICULT_DAY);

        Map<String, String> moods = moods("?q=search");
        assertThat(moods).containsEntry("s_fol", "NEED_DISTRACTION").containsEntry("s_other", "DIFFICULT_DAY");
    }

    @Test
    void availableAndStatusMood_areIndependent() throws Exception { // 11
        User both = person("both", "Both");
        activeOffering(both);
        activeStatus(both, StatusMood.WELL);
        User onlyAvailable = person("avail", "Only Available");
        activeOffering(onlyAvailable);
        User onlyMood = person("mood", "Only Mood");
        activeStatus(onlyMood, StatusMood.NEED_TO_TALK);
        person("neither", "Neither");

        JsonNode b = only("", "both");
        assertThat(b.get("available").asBoolean()).isTrue();
        assertThat(b.get("statusMood").asText()).isEqualTo("WELL");
        JsonNode a = only("", "avail");
        assertThat(a.get("available").asBoolean()).isTrue();
        assertThat(a.get("statusMood").isNull()).isTrue();
        JsonNode m = only("", "mood");
        assertThat(m.get("available").asBoolean()).isFalse();
        assertThat(m.get("statusMood").asText()).isEqualTo("NEED_TO_TALK");
        JsonNode n = only("", "neither");
        assertThat(n.get("available").asBoolean()).isFalse();
        assertThat(n.get("statusMood").isNull()).isTrue();
    }

    @Test
    void mixedUsers_haveCorrectMoods() throws Exception {
        activeStatus(person("x1", "Mixed One"), StatusMood.WELL);
        person("x2", "Mixed Two");
        expiredStatus(person("x3", "Mixed Three"), StatusMood.DIFFICULT_DAY);
        activeStatus(person("x4", "Mixed Four"), StatusMood.HERE_FOR_SOMEONE);
        activeStatus(privatePerson("x5", "Mixed Five"), StatusMood.WELL);

        Map<String, String> moods = moods("");
        assertThat(moods).containsEntry("x1", "WELL")
                .containsEntry("x2", null)
                .containsEntry("x3", null)
                .containsEntry("x4", "HERE_FOR_SOMEONE")
                .containsEntry("x5", null);
    }

    // ---------- empty / out of range ----------

    @Test
    void emptyPage_doesNotFail() throws Exception { // 12
        discover("").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pageBeyondEnd_keepsCurrentBehavior() throws Exception { // 13
        activeStatus(person("only", "Only"), StatusMood.WELL);

        discover("?page=4&size=5").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---------- contract: nullable enum, no extra status data ----------

    @Test
    void statusMood_isNullableEnum_neverAHasStatusBoolean() throws Exception { // 14
        person("plain", "Plain");

        String body = discover("").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body).get("content").get(0);
        assertThat(node.has("statusMood")).isTrue();
        assertThat(node.get("statusMood").isNull()).isTrue();
        assertThat(node.has("hasStatus")).isFalse();
        assertThat(body).doesNotContain("hasStatus");
    }

    @Test
    void response_doesNotExposeStatusDetails() throws Exception { // 15
        User user = person("detail", "Detail");
        Status saved = activeStatus(user, StatusMood.WELL);

        String body = discover("").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("statusId")
                .doesNotContain("createdAt")
                .doesNotContain("expiresAt")
                .doesNotContain("reactionCount")
                .doesNotContain("reactedByCurrentUser")
                .doesNotContain(saved.getId().toString());
    }

    @Test
    void followState_isNotAlteredByStatus() throws Exception { // 17
        User none = person("st_none", "St None");
        User req = privatePerson("st_req", "St Req");
        User fol = person("st_fol", "St Fol");
        followRequestRepository.save(FollowRequest.builder().requester(me).target(req).build());
        follow(me, fol);
        activeStatus(none, StatusMood.WELL);
        activeStatus(req, StatusMood.WELL);
        activeStatus(fol, StatusMood.WELL);

        assertThat(only("?q=st_", "st_none").get("followState").asText()).isEqualTo("NONE");
        assertThat(only("?q=st_", "st_req").get("followState").asText()).isEqualTo("REQUESTED");
        assertThat(only("?q=st_", "st_fol").get("followState").asText()).isEqualTo("FOLLOWING");
    }

    private Map<String, String> moods(String query) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (JsonNode n : content(query)) {
            JsonNode mood = n.get("statusMood");
            result.put(n.get("username").asText(), mood.isNull() ? null : mood.asText());
        }
        return result;
    }
}
