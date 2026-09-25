package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.ChangePasswordResponse;
import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Cambio de contrasena para un usuario YA autenticado -- distinto de
// PasswordResetService (forgot/reset), que es para alguien que perdio acceso
// a la cuenta. Aca la identidad viene del JWT (via UserPrincipal), nunca de
// un campo del body: no hay forma de que el request pida cambiar la
// contrasena de otro usuario.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ChangePasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Transactional
    public ChangePasswordResponse changePassword(UserPrincipal principal, String currentPassword, String newPassword) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        // BCrypt genera un salt distinto en cada encode, asi que nunca se
        // puede comparar el hash nuevo contra el guardado -- hay que usar
        // matches() contra el hash existente, igual que con currentPassword.
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be different from the current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        try {
            emailService.sendPasswordChangedEmail(user.getEmail(), user.getDisplayName());
        } catch (EmailDeliveryException e) {
            // Un fallo al avisar por email no puede revertir un cambio de
            // contrasena ya aplicado -- mismo criterio que PasswordResetService.
            log.warn("Could not send password-changed email for user {}: {}", user.getId(), e.getMessage());
        }

        return new ChangePasswordResponse("Password changed successfully.");
    }
}
