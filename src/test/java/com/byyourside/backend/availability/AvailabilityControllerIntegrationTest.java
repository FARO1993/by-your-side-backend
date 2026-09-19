package com.byyourside.backend.availability;

import com.byyourside.backend.chat.ConversationRepository;
import com.byyourside.backend.chat.MessageRepository;
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
class AvailabilityControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AvailabilityRepository availabilityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private User facu;
    private String facuToken;
    private User soumia;
    private String soumiaToken;

    @BeforeEach
    void setUp() throws Exception {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        availabilityRepository.deleteAll();
        userRepository.deleteAll();

        facu = registerUser("facu", "facu@example.com");
        facuToken = login("facu");
        soumia = registerUser("soumia", "soumia@example.com");
        soumiaToken = login("soumia");
    }

    private User registerUser(String username, String email) {
        User user = User.builder()
                .username(username).email(email)
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username).role(UserRole.USER).status(UserStatus.ACTIVE)
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
    void shouldSetAvailability_whenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/availability")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "TALK"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intent").value("TALK"));
    }

    @Test
    void shouldReplacePreviousAvailability_whenSettingNewOne() throws Exception {
        mockMvc.perform(post("/api/availability")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "TALK"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/availability")
                        .header("Authorization", "Bearer " + facuToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "MUSIC"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/availability/mine")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("MUSIC"));
    }

    @Test
    void shouldListAvailableUsers_byIntent() throws Exception {
        availabilityRepository.save(Availability.builder()
                .user(soumia).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.username").value("soumia"));
    }

    @Test
    void shouldExcludeExpiredAvailability_fromList() throws Exception {
        availabilityRepository.save(Availability.builder()
                .user(soumia).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldExcludeSelf_fromList() throws Exception {
        availabilityRepository.save(Availability.builder()
                .user(facu).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(get("/api/availability").param("intent", "TALK")
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldAllowStartingConversation_withAvailableStrangerButNotFollowed() throws Exception {
        availabilityRepository.save(Availability.builder()
                .user(soumia).intent(CompanionIntent.TALK)
                .expiresAt(Instant.now().plus(6, ChronoUnit.HOURS)).build());

        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnForbidden_whenStrangerHasNoActiveAvailability() throws Exception {
        mockMvc.perform(post("/api/conversations/{userId}", soumia.getId())
                        .header("Authorization", "Bearer " + facuToken))
                .andExpect(status().isForbidden());
    }
}