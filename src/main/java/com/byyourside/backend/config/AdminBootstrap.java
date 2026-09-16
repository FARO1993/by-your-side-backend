package com.byyourside.backend.config;

import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

// Bootstrap del primer ADMIN. Sin esto no hay forma de crear un admin via
// API (AdminUserController ya requiere ser ADMIN para promover a otros).
// Se activa solo si ADMIN_BOOTSTRAP_USERNAME esta seteado Y todavia no
// existe ningun ADMIN en la base -- no hace nada en arranques posteriores.
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;

    @Value("${app.admin-bootstrap-username:}")
    private String bootstrapUsername;

    @Override
    public void run(String... args) {
        if (bootstrapUsername == null || bootstrapUsername.isBlank()) {
            return;
        }

        if (userRepository.countByRole(UserRole.ADMIN) > 0) {
            return;
        }

        Optional<User> candidate = userRepository.findByUsername(bootstrapUsername);
        if (candidate.isEmpty()) {
            log.warn("ADMIN_BOOTSTRAP_USERNAME='{}' no matchea ningun usuario existente. " +
                    "Registralo primero y reiniciá el backend.", bootstrapUsername);
            return;
        }

        User user = candidate.get();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
        log.info("'{}' promovido a ADMIN por bootstrap inicial.", bootstrapUsername);
    }
}