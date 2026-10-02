package com.byyourside.backend.game;

import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.chat.Conversation;
import com.byyourside.backend.chat.ConversationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class GameRoomIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private FollowRepository followRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private UserBlockRepository userBlockRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired private GameRoomRepository gameRoomRepository;
    @Autowired private GameRoomEventRepository gameRoomEventRepository;

    private User facu;
    private User soumia;
    private User lu;
    private User stranger;
    private String facuToken;
    private String soumiaToken;
    private String luToken;
    private String strangerToken;

    @BeforeEach
    void setUp() throws Exception {
        gameRoomEventRepository.deleteAll();
        gameRoomRepository.deleteAll();
        conversationRepository.deleteAll();
        followRepository.deleteAll();
        userBlockRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu");
        soumia = registerUser("soumia");
        lu = registerUser("lu");
        stranger = registerUser("stranger");
        facuToken = login("facu");
        soumiaToken = login("soumia");
        luToken = login("lu");
        strangerToken = login("stranger");

        // facu sigue a soumia; con lu solo tiene una charla (p. ej. de Modo compañía).
        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
        User first = facu.getId().toString().compareTo(lu.getId().toString()) <= 0 ? facu : lu;
        User second = first.equals(facu) ? lu : facu;
        conversationRepository.save(Conversation.builder().userA(first).userB(second).build());
    }

    // ---------- invitaciones ----------

    @Test
    void invitesSomeoneYouFollow_andTheyCanSeeIt() throws Exception {
        String roomId = invite(facuToken, soumia.getId(), "MEMORY")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.game").value("MEMORY"))
                .andExpect(jsonPath("$.host.username").value("facu"))
                .andExpect(jsonPath("$.guest.username").value("soumia"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/game-rooms").header("Authorization", bearer(soumiaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(idOf(roomId)));
    }

    @Test
    void theInvitedPersonCanInviteBack_followInEitherDirectionCounts() throws Exception {
        invite(soumiaToken, facu.getId(), "PUZZLE").andExpect(status().isCreated());
    }

    @Test
    void anExistingConversationIsEnoughToInvite() throws Exception {
        invite(facuToken, lu.getId(), "GARDEN").andExpect(status().isCreated());
        invite(luToken, facu.getId(), "GARDEN").andExpect(status().isCreated());
    }

    @Test
    void cannotInviteStrangersOrYourself() throws Exception {
        invite(facuToken, stranger.getId(), "MEMORY").andExpect(status().isForbidden());
        invite(strangerToken, facu.getId(), "MEMORY").andExpect(status().isForbidden());
        invite(facuToken, facu.getId(), "MEMORY").andExpect(status().isBadRequest());
        invite(facuToken, UUID.randomUUID(), "MEMORY").andExpect(status().isNotFound());
    }

    @Test
    void cannotInviteWhenThereIsABlock_withTheSameMessageAsStrangers() throws Exception {
        mockMvc.perform(post("/api/users/{id}/block", facu.getId()).header("Authorization", bearer(soumiaToken)))
                .andExpect(status().is2xxSuccessful());
        invite(facuToken, soumia.getId(), "MEMORY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only invite people you already know"));
    }

    @Test
    void invitingAgainForTheSameGameReusesTheInvitation_andAnotherGameReplacesIt() throws Exception {
        String first = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        String again = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(first, again);

        String other = idOf(invite(facuToken, soumia.getId(), "PUZZLE").andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertNotEquals(first, other);
        room(facuToken, first)
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("CANCELLED"));
    }

    @Test
    void limitsPendingInvitations() throws Exception {
        for (int i = 0; i < GameRoomService.MAX_PENDING_INVITES; i++) {
            User friend = registerUser("friend" + i);
            followRepository.save(Follow.builder().follower(facu).following(friend).build());
            invite(facuToken, friend.getId(), "MEMORY").andExpect(status().isCreated());
        }
        invite(facuToken, soumia.getId(), "MEMORY").andExpect(status().isTooManyRequests());
    }

    @Test
    void invitationsExpire() throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        jdbcTemplate.update("UPDATE game_rooms SET created_at = created_at - interval '31 minutes' WHERE id = ?::uuid", roomId);

        action(soumiaToken, roomId, "accept").andExpect(status().isConflict());
        room(soumiaToken, roomId)
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("EXPIRED"));
        mockMvc.perform(get("/api/game-rooms").header("Authorization", bearer(soumiaToken)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- aceptar / rechazar / salir ----------

    @Test
    void onlyTheGuestAccepts_andThenTheGameIsActive() throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        action(facuToken, roomId, "accept").andExpect(status().isForbidden());
        action(soumiaToken, roomId, "accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
        action(soumiaToken, roomId, "accept").andExpect(status().isConflict());
    }

    @Test
    void outsidersCannotSeeOrTouchARoom() throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        room(strangerToken, roomId).andExpect(status().isNotFound());
        action(strangerToken, roomId, "accept").andExpect(status().isNotFound());
        action(strangerToken, roomId, "leave").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/game-rooms/{id}/events", roomId).header("Authorization", bearer(strangerToken)))
                .andExpect(status().isNotFound());
        event(strangerToken, roomId, "FLIP", Map.of("index", 1)).andExpect(status().isNotFound());
    }

    @Test
    void declining_endsTheInvitationGently() throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        action(soumiaToken, roomId, "decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("DECLINED"));
        action(soumiaToken, roomId, "accept").andExpect(status().isConflict());
    }

    @Test
    void theHostCanCancelAnInvitation_andAnyoneCanLeaveAnytime() throws Exception {
        String invited = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        action(facuToken, invited, "leave").andExpect(jsonPath("$.endReason").value("CANCELLED"));

        String roomId = startGame("PUZZLE");
        action(soumiaToken, roomId, "leave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("LEFT"));
        // Idempotente.
        action(facuToken, roomId, "leave").andExpect(status().isOk()).andExpect(jsonPath("$.endReason").value("LEFT"));
        event(facuToken, roomId, "PLACE", Map.of("piece", 3)).andExpect(status().isConflict());
    }

    // ---------- jugadas ----------

    @Test
    void movesAreOrderedAndCanBeReplayed() throws Exception {
        String roomId = startGame("MEMORY");

        event(facuToken, roomId, "FLIP", Map.of("index", 4))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.actorId").value(facu.getId().toString()))
                .andExpect(jsonPath("$.payload.index").value(4));
        event(soumiaToken, roomId, "FLIP", Map.of("index", 7))
                .andExpect(jsonPath("$.seq").value(2));

        mockMvc.perform(get("/api/game-rooms/{id}/events", roomId).header("Authorization", bearer(soumiaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].type").value("FLIP"))
                .andExpect(jsonPath("$[1].payload.index").value(7));
        mockMvc.perform(get("/api/game-rooms/{id}/events", roomId).param("after", "1").header("Authorization", bearer(facuToken)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].seq").value(2));
        room(facuToken, roomId).andExpect(jsonPath("$.eventCount").value(2));
    }

    @Test
    void noMovesBeforeTheInvitationIsAccepted() throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), "MEMORY").andReturn().getResponse().getContentAsString());
        event(facuToken, roomId, "FLIP", Map.of("index", 1)).andExpect(status().isConflict());
    }

    @Test
    void rejectsBadMoveTypesAndOversizedPayloads() throws Exception {
        String roomId = startGame("GARDEN");
        event(facuToken, roomId, "lowercase", Map.of()).andExpect(status().isBadRequest());
        event(facuToken, roomId, "", Map.of()).andExpect(status().isBadRequest());
        event(facuToken, roomId, "WATER", Map.of("blob", "x".repeat(GameRoomService.MAX_PAYLOAD_CHARS + 10)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/game-rooms/{id}/events", roomId)
                        .header("Authorization", bearer(facuToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WATER\"}"))
                .andExpect(status().isBadRequest());
        room(facuToken, roomId).andExpect(jsonPath("$.eventCount").value(0));
    }

    @Test
    void aBlockEndsOpenGamesWithoutSayingWhy() throws Exception {
        String roomId = startGame("MEMORY");
        String pending = idOf(invite(soumiaToken, facu.getId(), "PUZZLE").andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/api/users/{id}/block", soumia.getId()).header("Authorization", bearer(facuToken)))
                .andExpect(status().is2xxSuccessful());

        room(soumiaToken, roomId)
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("UNAVAILABLE"));
        room(facuToken, pending).andExpect(jsonPath("$.endReason").value("UNAVAILABLE"));
        event(soumiaToken, roomId, "FLIP", Map.of("index", 1)).andExpect(status().isConflict());
    }

    @Test
    void idleGamesExpire() throws Exception {
        String roomId = startGame("MEMORY");
        jdbcTemplate.update("UPDATE game_rooms SET last_activity_at = last_activity_at - interval '7 hours' WHERE id = ?::uuid", roomId);
        event(facuToken, roomId, "FLIP", Map.of("index", 1)).andExpect(status().isConflict());
        room(facuToken, roomId).andExpect(jsonPath("$.endReason").value("EXPIRED"));
    }

    // ---------- helpers ----------

    private String startGame(String game) throws Exception {
        String roomId = idOf(invite(facuToken, soumia.getId(), game).andReturn().getResponse().getContentAsString());
        action(soumiaToken, roomId, "accept").andExpect(status().isOk());
        return roomId;
    }

    private ResultActions invite(String token, UUID guestId, String game) throws Exception {
        return mockMvc.perform(post("/api/game-rooms")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("game", game, "guestId", guestId.toString()))));
    }

    private ResultActions room(String token, String roomId) throws Exception {
        return mockMvc.perform(get("/api/game-rooms/{id}", roomId).header("Authorization", bearer(token)));
    }

    private ResultActions action(String token, String roomId, String action) throws Exception {
        return mockMvc.perform(post("/api/game-rooms/{id}/" + action, roomId).header("Authorization", bearer(token)));
    }

    private ResultActions event(String token, String roomId, String type, Map<String, ?> payload) throws Exception {
        return mockMvc.perform(post("/api/game-rooms/{id}/events", roomId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("type", type, "payload", payload))));
    }

    private String idOf(String json) throws Exception {
        return objectMapper.readTree(json).get("id").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private User registerUser(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private String login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", username + "@example.com", "password", "secretpass123"));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
