package com.byyourside.backend.user;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Backend Debt B5.1: Discover server-side (q opcional, browse vs search,
// orden estable, paginacion validada, exclusiones DB-side).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DiscoverSearchIntegrationTest {

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
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();

        me = createUser("me", "Me Myself", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);
        meToken = login("me");
    }

    // ---------- helpers ----------

    private User createUser(String username, String displayName, UserRole role, UserStatus status,
                            ProfileVisibility visibility, String bio) {
        return userRepository.save(User.builder()
                .username(username).email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(displayName).bio(bio)
                .role(role).status(status).profileVisibility(visibility)
                .build());
    }

    private User person(String username, String displayName) {
        return createUser(username, displayName, UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);
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

    private ResultActions discover(String token, String query) throws Exception {
        return mockMvc.perform(get(java.net.URI.create("/api/users/discover" + query)).header("Authorization", "Bearer " + token));
    }

    private JsonNode discoverJson(String token, String query) throws Exception {
        String body = discover(token, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private List<String> usernames(String query) throws Exception {
        List<String> result = new ArrayList<>();
        for (JsonNode n : discoverJson(meToken, query).get("content")) {
            result.add(n.get("username").asText());
        }
        return result;
    }

    private void follow(User follower, User target) {
        followRepository.save(Follow.builder().follower(follower).following(target).build());
    }

    // ---------- search: matching ----------

    @Test
    void search_partialCaseInsensitive_matchesDisplayName() throws Exception {
        person("fac_u", "Facundo");
        person("otro", "Otro");

        assertThat(usernames("?q=fac")).containsExactly("fac_u");
        assertThat(usernames("?q=FAC")).containsExactly("fac_u");
        assertThat(usernames("?q=CUND")).containsExactly("fac_u");
    }

    @Test
    void search_matchesUsername() throws Exception {
        person("zelda99", "Link");

        assertThat(usernames("?q=zelda")).containsExactly("zelda99");
    }

    @Test
    void search_trimsAndCollapsesWhitespace() throws Exception {
        person("juanp", "Juan Perez");

        assertThat(usernames("?q=%20%20juan%20%20%20perez%20")).containsExactly("juanp");
    }

    @Test
    void search_doesNotMatchByEmailOrBio() throws Exception {
        createUser("plain", "Plain", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, "amantedelcafe");

        assertThat(usernames("?q=example.com")).isEmpty();
        assertThat(usernames("?q=plain@")).isEmpty();
        assertThat(usernames("?q=amantedelcafe")).isEmpty();
    }

    @Test
    void search_doesNotMatchByBio_ofPrivateProfile() throws Exception {
        createUser("secreto", "Secreto", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PRIVATE, "bio-oculta-xyz");

        assertThat(usernames("?q=bio-oculta-xyz")).isEmpty();
    }

    @Test
    void search_wildcardsAreLiteral() throws Exception {
        person("pct", "100% real");
        person("under", "a_b");
        person("normal", "Normal");
        person("slash", "back\\slash");

        assertThat(usernames("?q=%25")).containsExactly("pct");            // %
        assertThat(usernames("?q=_")).containsExactly("under");            // _ (username 'under' tambien no lo contiene)
        assertThat(usernames("?q=%5C")).containsExactly("slash");          // \
        assertThat(usernames("?q=a_b")).containsExactly("under");
    }

    @Test
    void search_noMatch_returnsEmptyPage() throws Exception {
        person("someone", "Someone");

        discover(meToken, "?q=zzzz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------- empty query = browse ----------

    @Test
    void emptyOrBlankQuery_behavesAsBrowse() throws Exception {
        User followed = person("followed", "Followed");
        person("other", "Other");
        follow(me, followed);

        assertThat(usernames("")).containsExactly("other");
        assertThat(usernames("?q=")).containsExactly("other");
        assertThat(usernames("?q=%20%20%20")).containsExactly("other");
    }

    // ---------- browse vs search: followed ----------

    @Test
    void browse_excludesFollowed_search_includesFollowedAsFollowing() throws Exception {
        User followed = person("carla", "Carla");
        follow(me, followed);

        assertThat(usernames("")).doesNotContain("carla");

        JsonNode content = discoverJson(meToken, "?q=carla").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("username").asText()).isEqualTo("carla");
        assertThat(content.get(0).get("followState").asText()).isEqualTo("FOLLOWING");
    }

    @Test
    void followState_none_requested_following_inSearch() throws Exception {
        User a = person("st_none", "St None");
        User b = person("st_req", "St Req");
        User c = person("st_fol", "St Fol");
        createUser("st_priv", "St Priv", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PRIVATE, "x");
        followRequestRepository.save(FollowRequest.builder().requester(me).target(b).build());
        follow(me, c);

        JsonNode content = discoverJson(meToken, "?q=st_").get("content");
        java.util.Map<String, String> states = new java.util.HashMap<>();
        for (JsonNode n : content) {
            states.put(n.get("username").asText(), n.get("followState").asText());
        }
        assertThat(states).containsEntry("st_none", "NONE")
                .containsEntry("st_req", "REQUESTED")
                .containsEntry("st_fol", "FOLLOWING")
                .containsEntry("st_priv", "NONE");
        assertThat(a).isNotNull();
    }

    @Test
    void browse_requestedIsPreserved() throws Exception {
        User target = createUser("target", "Target", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PRIVATE, null);
        followRequestRepository.save(FollowRequest.builder().requester(me).target(target).build());

        JsonNode content = discoverJson(meToken, "").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("followState").asText()).isEqualTo("REQUESTED");
    }

    // ---------- exclusions ----------

    @Test
    void currentUser_isNeverListed() throws Exception {
        person("other", "Me Too");

        assertThat(usernames("")).doesNotContain("me");
        assertThat(usernames("?q=me")).doesNotContain("me").contains("other");
    }

    @Test
    void block_isBilateral_inBrowseAndSearch() throws Exception {
        User iBlocked = person("i_blocked", "Blocked One");
        User blockedMe = person("blocked_me", "Blocked Two");
        person("visible", "Visible");
        userBlockRepository.save(UserBlock.builder().blocker(me).blocked(iBlocked).build());
        userBlockRepository.save(UserBlock.builder().blocker(blockedMe).blocked(me).build());

        assertThat(usernames("")).containsExactly("visible");
        assertThat(usernames("?q=blocked")).isEmpty();
    }

    @Test
    void block_winsOverFollow_inSearch() throws Exception {
        User target = person("both", "Both");
        follow(me, target);
        userBlockRepository.save(UserBlock.builder().blocker(me).blocked(target).build());

        assertThat(usernames("?q=both")).isEmpty();
    }

    @Test
    void mute_isUnilateral() throws Exception {
        User muted = person("muted", "Muted");
        userMuteRepository.save(UserMute.builder().muter(me).muted(muted).build());

        assertThat(usernames("")).doesNotContain("muted");
        assertThat(usernames("?q=muted")).isEmpty();

        // El muteado sigue viendo al muter.
        String mutedToken = login("muted");
        List<String> seenByMuted = new ArrayList<>();
        for (JsonNode n : discoverJson(mutedToken, "").get("content")) {
            seenByMuted.add(n.get("username").asText());
        }
        assertThat(seenByMuted).contains("me");
    }

    @Test
    void accountStatus_onlyActiveIsListed() throws Exception {
        createUser("suspended", "Zed Suspended", UserRole.USER, UserStatus.SUSPENDED, ProfileVisibility.PUBLIC, null);
        createUser("deactivated", "Zed Deactivated", UserRole.USER, UserStatus.DEACTIVATED, ProfileVisibility.PUBLIC, null);
        person("active", "Zed Active");

        assertThat(usernames("")).containsExactly("active");
        assertThat(usernames("?q=zed")).containsExactly("active");
    }

    @Test
    void role_doesNotAffectDiscoverability() throws Exception {
        createUser("r_user", "R User", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);
        createUser("r_mod", "R Moderator", UserRole.MODERATOR, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);
        createUser("r_admin", "R Admin", UserRole.ADMIN, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);

        assertThat(usernames("")).containsExactlyInAnyOrder("r_user", "r_mod", "r_admin");
        assertThat(usernames("?q=r_")).containsExactlyInAnyOrder("r_user", "r_mod", "r_admin");
    }

    // ---------- privacy / DTO ----------

    @Test
    void privateProfile_appearsLimited_bioNull_evenWhenFollowing() throws Exception {
        User priv = createUser("priv", "Priv", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PRIVATE, "bio secreta");
        follow(me, priv);

        JsonNode content = discoverJson(meToken, "?q=priv").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("bio").isNull()).isTrue();
        assertThat(content.get(0).get("profileVisibility").asText()).isEqualTo("PRIVATE");
    }

    @Test
    void publicProfile_showsBio() throws Exception {
        createUser("pub", "Pub", UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, "hola");

        discover(meToken, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bio").value("hola"));
    }

    @Test
    void response_neverLeaksEmail() throws Exception {
        person("leak", "Leak");

        String body = discover(meToken, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("email").doesNotContain("@example.com");
    }

    // ---------- ordering & pagination ----------

    @Test
    void ordering_isByLowerDisplayNameThenId_withUsernameFallback() throws Exception {
        person("u1", "bruno");
        person("u2", "Alma");
        person("u3", "carla");
        createUser("aaa_no_name", null, UserRole.USER, UserStatus.ACTIVE, ProfileVisibility.PUBLIC, null);

        // "aaa_no_name" cae por username cuando displayName es null.
        assertThat(usernames("")).containsExactly("aaa_no_name", "u2", "u1", "u3");
    }

    @Test
    void ordering_tiesBrokenById_andStableAcrossCalls() throws Exception {
        for (int i = 0; i < 6; i++) {
            person("same" + i, "Same Name");
        }

        List<String> first = usernames("");
        List<String> second = usernames("");
        assertThat(first).hasSize(6).isEqualTo(second);
    }

    @Test
    void pagination_walksAllPagesWithoutDuplicatesOrGaps() throws Exception {
        for (int i = 0; i < 5; i++) {
            person("pg" + i, "Person " + i);
        }

        List<String> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode json = discoverJson(meToken, "?size=2&page=" + page);
            assertThat(json.get("totalElements").asInt()).isEqualTo(5);
            assertThat(json.get("totalPages").asInt()).isEqualTo(3);
            assertThat(json.get("last").asBoolean()).isEqualTo(page == 2);
            for (JsonNode n : json.get("content")) {
                all.add(n.get("username").asText());
            }
        }
        assertThat(all).containsExactly("pg0", "pg1", "pg2", "pg3", "pg4");
    }

    @Test
    void pagination_pageBeyondEnd_isEmptyButKeepsTotal() throws Exception {
        person("x1", "X1");

        discover(meToken, "?page=5&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void defaultSize_is20_andMaxSize50IsAccepted() throws Exception {
        for (int i = 0; i < 25; i++) {
            person(String.format("d%02d", i), String.format("D%02d", i));
        }

        discover(meToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content.length()").value(20));
        discover(meToken, "?size=50").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(25));
    }

    @Test
    void pagination_countMatchesSearchFilter() throws Exception {
        person("m1", "Match One");
        person("m2", "Match Two");
        person("n1", "Other");

        discover(meToken, "?q=match&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // ---------- validation -> 400 ----------

    @Test
    void invalidPagingParams_return400() throws Exception {
        discover(meToken, "?page=-1").andExpect(status().isBadRequest());
        discover(meToken, "?size=0").andExpect(status().isBadRequest());
        discover(meToken, "?size=-3").andExpect(status().isBadRequest());
        discover(meToken, "?size=51").andExpect(status().isBadRequest());
    }

    @Test
    void malformedPagingParams_return400() throws Exception {
        discover(meToken, "?size=abc").andExpect(status().isBadRequest());
        discover(meToken, "?page=x").andExpect(status().isBadRequest());
    }

    @Test
    void queryLongerThan50_returns400_exactly50IsOk() throws Exception {
        discover(meToken, "?q=" + "a".repeat(51)).andExpect(status().isBadRequest());
        discover(meToken, "?q=" + "a".repeat(50)).andExpect(status().isOk());
    }

    @Test
    void queryLength_isMeasuredAfterNormalization() throws Exception {
        // 50 'a' rodeadas de espacios: normalizado = 50 => valido.
        discover(meToken, "?q=%20%20" + "a".repeat(50) + "%20%20").andExpect(status().isOk());
    }

    @Test
    void withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/discover?q=a")).andExpect(status().isUnauthorized());
    }
}
