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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Backend Debt B5.2: `available: boolean` en Discover (Offering activo,
// resuelto en batch). Cubre browse/search, PRIVATE, followState, block/mute,
// Offering expirado y que nunca se exponga el OfferingType.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DiscoverAvailabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
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

    private User me;
    private String meToken;

    @BeforeEach
    void setUp() throws Exception {
        companionOfferingRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();

        me = createUser("me", "Me Myself", ProfileVisibility.PUBLIC, null);
        meToken = login("me");
    }

    // ---------- helpers ----------

    private User createUser(String username, String displayName, ProfileVisibility visibility, String bio) {
        return userRepository.save(User.builder()
                .username(username).email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(displayName).bio(bio)
                .role(UserRole.USER).status(UserStatus.ACTIVE).profileVisibility(visibility)
                .build());
    }

    private User person(String username, String displayName) {
        return createUser(username, displayName, ProfileVisibility.PUBLIC, null);
    }

    private void activeOffering(User user) {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(user).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
    }

    private void expiredOffering(User user) {
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(user).type(OfferingType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
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

    // ---------- available semantics ----------

    @Test
    void userWithActiveOffering_isAvailable() throws Exception { // A
        activeOffering(person("active", "Active"));

        assertThat(only("", "active").get("available").asBoolean()).isTrue();
    }

    @Test
    void userWithoutOffering_isNotAvailable() throws Exception { // B
        person("idle", "Idle");

        assertThat(only("", "idle").get("available").asBoolean()).isFalse();
    }

    @Test
    void expiredOffering_isNotAvailable_evenIfRowStillExists() throws Exception { // C
        User user = person("expired", "Expired");
        expiredOffering(user);
        assertThat(companionOfferingRepository.count()).isEqualTo(1);

        assertThat(only("", "expired").get("available").asBoolean()).isFalse();
    }

    @Test
    void browse_withActiveOffering_isAvailable() throws Exception { // I
        activeOffering(person("b_user", "Browse User"));

        assertThat(only("", "b_user").get("available").asBoolean()).isTrue();
    }

    @Test
    void search_withActiveOffering_isAvailable() throws Exception { // J
        activeOffering(person("s_user", "Search User"));

        assertThat(only("?q=search", "s_user").get("available").asBoolean()).isTrue();
    }

    @Test
    void mixedUsers_haveCorrectAvailability() throws Exception { // L
        activeOffering(person("m_on1", "M On One"));
        person("m_off1", "M Off One");
        activeOffering(person("m_on2", "M On Two"));
        expiredOffering(person("m_exp", "M Expired"));
        person("m_off2", "M Off Two");

        Map<String, Boolean> result = new HashMap<>();
        for (JsonNode n : content("")) {
            result.put(n.get("username").asText(), n.get("available").asBoolean());
        }
        assertThat(result)
                .containsEntry("m_on1", true)
                .containsEntry("m_on2", true)
                .containsEntry("m_off1", false)
                .containsEntry("m_off2", false)
                .containsEntry("m_exp", false);
    }

    @Test
    void availableIsAlwaysABoolean_neverNull() throws Exception {
        person("plain", "Plain");

        discover("").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].available").isBoolean());
    }

    // ---------- PRIVATE / followState ----------

    @Test
    void privateWithActiveOffering_bioNull_availableTrue() throws Exception { // D
        activeOffering(createUser("priv", "Priv", ProfileVisibility.PRIVATE, "bio secreta"));

        JsonNode node = only("", "priv");
        assertThat(node.get("bio").isNull()).isTrue();
        assertThat(node.get("available").asBoolean()).isTrue();
        assertThat(node.get("profileVisibility").asText()).isEqualTo("PRIVATE");
    }

    @Test
    void followingFoundViaSearch_withActiveOffering() throws Exception { // E
        User followed = person("fol", "Followed Person");
        followRepository.save(Follow.builder().follower(me).following(followed).build());
        activeOffering(followed);

        JsonNode node = only("?q=followed", "fol");
        assertThat(node.get("followState").asText()).isEqualTo("FOLLOWING");
        assertThat(node.get("available").asBoolean()).isTrue();
    }

    @Test
    void requestedWithActiveOffering() throws Exception { // F
        User target = createUser("req", "Requested Person", ProfileVisibility.PRIVATE, null);
        followRequestRepository.save(FollowRequest.builder().requester(me).target(target).build());
        activeOffering(target);

        JsonNode node = only("", "req");
        assertThat(node.get("followState").asText()).isEqualTo("REQUESTED");
        assertThat(node.get("available").asBoolean()).isTrue();
    }

    // ---------- block / mute: siguen excluyendo (no llegan al batch) ----------

    @Test
    void blockedUserWithActiveOffering_doesNotAppear() throws Exception { // G
        User blocked = person("blk", "Blocked One");
        User blocker = person("blkr", "Blocker One");
        activeOffering(blocked);
        activeOffering(blocker);
        userBlockRepository.save(UserBlock.builder().blocker(me).blocked(blocked).build());
        userBlockRepository.save(UserBlock.builder().blocker(blocker).blocked(me).build());

        assertThat(content("").size()).isZero();
        assertThat(content("?q=one").size()).isZero();
    }

    @Test
    void mutedUserWithActiveOffering_doesNotAppear() throws Exception { // H
        User muted = person("mut", "Muted One");
        activeOffering(muted);
        userMuteRepository.save(UserMute.builder().muter(me).muted(muted).build());

        assertThat(content("").size()).isZero();
        assertThat(content("?q=muted").size()).isZero();
    }

    // ---------- pagina vacia / no type leak ----------

    @Test
    void emptyPage_returns200WithEmptyContent() throws Exception { // K
        discover("").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pageBeyondEnd_returns200WithEmptyContent() throws Exception {
        activeOffering(person("only", "Only"));

        discover("?page=3&size=5").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void response_neverExposesOfferingTypeOrNeed() throws Exception {
        activeOffering(person("typed", "Typed"));

        String body = discover("").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("offeringType").doesNotContain("LISTEN")
                .doesNotContain("TALK").doesNotContain("DISTRACT")
                .doesNotContain("need").doesNotContain("expiresAt");
    }
}
