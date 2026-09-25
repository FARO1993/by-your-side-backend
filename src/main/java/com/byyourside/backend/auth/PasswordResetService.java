package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.ForgotPasswordResponse;
import com.byyourside.backend.auth.dto.ResetPasswordResponse;
import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

// Mismo patron que EmailVerificationService (hash SHA-256 del token, respuesta
// publica siempre generica, cooldown persistido, invalidacion de tokens
// pendientes anteriores) aplicado a recuperacion de contrasena. Se mantiene
// como service separado -- y con su propia entidad/tabla, ver
// PasswordResetToken -- porque son dos credenciales de un solo uso con ciclos
// de vida distintos, aunque la forma sea casi identica.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PasswordResetService {

    private static final long EXPIRATION_MINUTES = 30;
    private static final int TOKEN_BYTES = 32;

    // Mismo valor y misma razon que EmailVerificationService: evita que un
    // cliente dispare cientos de emails inmediatos para la misma cuenta, sin
    // requerir Redis ni infraestructura distribuida.
    private static final long RESEND_COOLDOWN_SECONDS = 60;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuthSessionService authSessionService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // Respuesta publica siempre generica: no revela si el email existe, ni si
    // esta rate-limited -- ambas situaciones terminan en el mismo mensaje y el
    // mismo no-op silencioso puertas adentro. No requiere que la cuenta tenga
    // el email verificado (perder acceso a la cuenta no depende de ese estado).
    @Transactional
    public ForgotPasswordResponse forgotPassword(String email) {
        userRepository.findByEmail(email)
                .filter(this::isOutsideCooldown)
                .ifPresent(user -> {
                    tokenRepository.invalidatePendingTokensForUser(user.getId());
                    issue(user);
                });

        return new ForgotPasswordResponse(
                "If an account with that email exists, we've sent password reset instructions.");
    }

    @Transactional
    public ResetPasswordResponse resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid password reset token"));

        if (token.isUsed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Password reset token has already been used");
        }

        if (token.isInvalidated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password reset token is no longer valid; a newer one may have been requested");
        }

        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password reset token has expired");
        }

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Decision de producto (Fase 1.5): un reset de contrasena cierra
        // TODAS las sesiones del usuario -- si alguien pudo resetear la
        // contrasena es porque tenia acceso al email, pero cualquier sesion
        // ya abierta con la contrasena vieja (por ejemplo, la de un atacante
        // que la tenia comprometida) no debe sobrevivir a un reset. Misma
        // transaccion que el cambio de contrasena.
        authSessionService.revokeAllForUser(user.getId());

        try {
            emailService.sendPasswordChangedEmail(user.getEmail(), user.getDisplayName());
        } catch (EmailDeliveryException e) {
            // Un fallo al avisar por email no puede revertir un cambio de
            // contrasena ya aplicado -- el usuario ya puede loguearse con la
            // nueva.
            log.warn("Could not send password-changed email for user {}: {}", user.getId(), e.getMessage());
        }

        return new ResetPasswordResponse("Your password has been reset successfully.");
    }

    private void issue(User user) {
        String rawToken = generateRawToken();

        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(EXPIRATION_MINUTES, ChronoUnit.MINUTES))
                .build();

        tokenRepository.save(token);

        String resetUrl = buildResetUrl(rawToken);
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), user.getDisplayName(), resetUrl);
        } catch (EmailDeliveryException e) {
            // Una caida de Resend no puede tirar abajo la solicitud -- el
            // token ya quedo persistido igual y la respuesta HTTP sigue
            // siendo la misma generica de siempre.
            log.warn("Could not send password reset email for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private boolean isOutsideCooldown(User user) {
        return tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .map(PasswordResetToken::getCreatedAt)
                .map(lastIssuedAt -> lastIssuedAt.isBefore(Instant.now().minusSeconds(RESEND_COOLDOWN_SECONDS)))
                .orElse(true);
    }

    private String buildResetUrl(String rawToken) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return base + "/reset-password?token=" + encodedToken;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
