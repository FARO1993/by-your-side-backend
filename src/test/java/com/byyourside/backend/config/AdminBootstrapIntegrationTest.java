package com.byyourside.backend.config;

import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import com.byyourside.backend.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.admin-bootstrap-username=bootstrapadmin")
class AdminBootstrapIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AdminBootstrap adminBootstrap;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    private User registerUser(String username, UserRole role) {
        User user = User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("secretpass123"))
                .displayName(username)
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }

    @Test
    void shouldPromoteMatchingUser_whenNoAdminExistsYet() {
        registerUser("bootstrapadmin", UserRole.USER);

        // Simula el reinicio del backend con ADMIN_BOOTSTRAP_USERNAME=bootstrapadmin ya seteado.
        adminBootstrap.run();

        User promoted = userRepository.findByUsername("bootstrapadmin").orElseThrow();
        assertEquals(UserRole.ADMIN, promoted.getRole());
    }

    @Test
    void shouldNotFail_whenUsernameDoesNotMatchAnyUser() {
        // No hay ningun usuario "bootstrapadmin" registrado todavia.
        adminBootstrap.run();

        assertEquals(0, userRepository.countByRole(UserRole.ADMIN));
    }

    @Test
    void shouldNotPromote_whenAnAdminAlreadyExists() {
        registerUser("otroadmin", UserRole.ADMIN);
        registerUser("bootstrapadmin", UserRole.USER);

        adminBootstrap.run();

        User notPromoted = userRepository.findByUsername("bootstrapadmin").orElseThrow();
        assertEquals(UserRole.USER, notPromoted.getRole());
    }
}