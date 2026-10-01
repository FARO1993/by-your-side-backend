package com.byyourside.backend.chat;

import com.byyourside.backend.block.UserBlock;
import com.byyourside.backend.block.UserBlockRepository;
import com.byyourside.backend.mute.UserMuteRepository;
import com.byyourside.backend.auth.EmailVerificationTokenRepository;
import com.byyourside.backend.auth.AuthSessionRepository;
import com.byyourside.backend.companion.CompanionNeed;
import com.byyourside.backend.companion.CompanionNeedRepository;
import com.byyourside.backend.companion.CompanionOffering;
import com.byyourside.backend.companion.CompanionOfferingRepository;
import com.byyourside.backend.companion.NeedType;
import com.byyourside.backend.companion.OfferingType;
import com.byyourside.backend.follow.Follow;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChatControllerIntegrationTest {

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
    private CompanionOfferingRepository companionOfferingRepository;

    @Autowired
    private CompanionNeedRepository companionNeedRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;
    private User stranger;
    private String strangerToken;

    @BeforeEach
    void setUp() throws Exception {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        companionOfferingRepository.deleteAll();
        companionNeedRepository.deleteAll();
        followRequestRepository.deleteAll();
        followRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
        stranger = registerUser("stranger", "stranger@example.com");
        strangerToken = login("stranger");

        followRepository.save(Follow.builder().follower(facu).following(soumia).build());
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

    private String createConversation(String token, java.util.UUID otherUserId) throws Exception {
        String response = mockMvc.perform(post("/api/conversations/{userId}", otherUserId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void shouldCreateConversation_whenFollowingTargetUser() throws Exception {
        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherUser.username").value("soumia"))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void shouldReturnSameConversation_regardlessOfWhoInitiates() throws Exception {
        String idFromFacu = createConversation(facuToken, soumia.getId());
        String idFromSoumia = createConversation(soumiaToken, facu.getId());

        org.junit.jupiter.api.Assertions.assertEquals(idFromFacu, idFromSoumia);
    }

    @Test
    void shouldReturnForbidden_whenNoFollowRelationshipExists() throws Exception {
        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnBadRequest_whenMessagingSelf() throws Exception {
        mockMvc.perform(post("/api/conversations/{userId}", facu.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldSendAndListMessages() throws Exception {
        String conversationId = createConversation(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "hola, como estas?"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hola, como estas?"))
                .andExpect(jsonPath("$.sender.username").value("facu"));

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("hola, como estas?"));
    }

    @Test
    void shouldReturnForbidden_whenNonParticipantReadsMessages() throws Exception {
        String conversationId = createConversation(facuToken, soumia.getId());

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReflectUnreadCount_inConversationsList() throws Exception {
        String conversationId = createConversation(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "mensaje sin leer"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unreadCount").value(1));
    }

    @Test
    void shouldMarkMessagesAsRead_afterFetchingThem() throws Exception {
        String conversationId = createConversation(facuToken, soumia.getId());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "mensaje"}
                                """))
                .andExpect(status().isCreated());

        // soumia lee los mensajes -> deberian marcarse como leidos
        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + soumiaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unreadCount").value(0));
    }

    @Test
    void shouldReturnUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================
    // Backend Debt B4B.3: ChatService ahora consulta
    // CompanionOfferingService.hasActiveOffering en vez de la extinta
    // AvailabilityRepository -- companion_offerings es la unica fuente de
    // verdad. AB/AC (stranger con/sin Offering activa) ya estan cubiertos
    // en AvailabilityControllerIntegrationTest
    // (shouldAllowStartingConversation_withAvailableStrangerButNotFollowed /
    // shouldReturnForbidden_whenStrangerHasNoActiveAvailability). AH/AI
    // (path de follow sin Offering / Conversation canonica) ya estan
    // cubiertos arriba por shouldCreateConversation_whenFollowingTargetUser
    // / shouldReturnSameConversation_regardlessOfWhoInitiates.
    // ========================================================

    @Test
    void shouldDenyConversation_whenTargetOfferingExpired() throws Exception { // AD
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(stranger).type(OfferingType.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowConversation_withPrivateStranger_thatHasActiveOffering() throws Exception { // AE
        stranger.setProfileVisibility(ProfileVisibility.PRIVATE);
        userRepository.save(stranger);
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(stranger).type(OfferingType.LISTEN)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldDenyConversation_viaOffering_whenViewerBlockedTarget() throws Exception { // AF
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(stranger).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        userBlockRepository.save(UserBlock.builder().blocker(facu).blocked(stranger).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyConversation_viaOffering_whenTargetBlockedViewer() throws Exception { // AG
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(stranger).type(OfferingType.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());
        userBlockRepository.save(UserBlock.builder().blocker(stranger).blocked(facu).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void needAloneDoesNotAuthorizeChat_whenTargetHasNoOffering() throws Exception { // AJ
        // facu tiene un Need compatible con TALK, pero stranger no tiene
        // ninguna Offering activa -- Need nunca es control de acceso a chat.
        companionNeedRepository.save(CompanionNeed.builder()
                .user(facu).type(NeedType.TALK)
                .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void targetOfferingAlone_authorizesChat_regardlessOfCallerNeed() throws Exception { // AK
        // facu no tiene ningun Need activo, pero stranger si tiene Offering
        // activa -- alcanza igual, ChatService no exige compatibilidad.
        companionOfferingRepository.save(CompanionOffering.builder()
                .user(stranger).type(OfferingType.DISTRACT)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", stranger.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }
}