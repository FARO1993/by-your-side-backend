package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.EmailVerificationResponse;
import com.byyourside.backend.email.EmailDeliveryException;
import com.byyourside.backend.email.EmailService;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EmailVerificationService {

    private static final long EXPIRATION_HOURS = 24;
    private static final int TOKEN_BYTES = 32;

    // Antiabuso simple para resend-verification: como minimo evita que un
    // cliente dispare cientos de emails inmediatos para la misma cuenta, sin
    // requerir Redis ni infraestructura distribuida -- se apoya en el token
    // mas reciente ya persistido, no en estado en memoria.
    private static final long RESEND_COOLDOWN_SECONDS = 60;

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // Genera y persiste un nuevo token para el usuario dado, devuelve el
    // valor real (nunca se guarda en la base, solo su hash) para que el
    // caller pueda usarlo en tests -- en produccion su unico destino es el
    // email de verificacion armado acá mismo. Nunca se loguea ni se expone
    // por HTTP.
    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();

        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build();

        tokenRepository.save(token);

        String verificationUrl = buildVerificationUrl(rawToken);
        try {
            emailService.sendVerificationEmail(user.getEmail(), user.getDisplayName(), verificationUrl);
        } catch (EmailDeliveryException e) {
            // Una caida de Resend no puede tirar abajo un registro valido --
            // el usuario y su token ya quedaron persistidos igual.
            log.warn("Could not send verification email for user {}: {}", user.getId(), e.getMessage());
        }

        return rawToken;
    }

    // Respuesta publica siempre generica (ver AuthController): no revela si
    // el email existe, si ya esta verificado, ni si esta rate-limited --
    // las tres situaciones terminan en el mismo no-op silencioso.
    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(email)
                .filter(user -> !user.isEmailVerified())
                .filter(this::isOutsideResendCooldown)
                .ifPresent(user -> {
                    tokenRepository.invalidatePendingTokensForUser(user.getId());
                    issue(user);
                });
    }

    @Transactional
    public EmailVerificationResponse verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token"));

        if (token.isUsed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification token has already been used");
        }

        if (token.isInvalidated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Verification token is no longer valid; a newer one may have been requested");
        }

        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has expired");
        }

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);

        User user = token.getUser();

        // Idempotente: si el usuario ya estaba verificado (por otro token
        // valido, ej. reenviado), no se pisa la fecha original de verificacion
        // ni se reenvia el email de bienvenida.
        boolean justVerified = !user.isEmailVerified();
        if (justVerified) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);

            try {
                emailService.sendWelcomeEmail(user.getEmail(), user.getDisplayName());
            } catch (EmailDeliveryException e) {
                // Una caida de Resend no puede revertir una verificacion valida.
                log.warn("Could not send welcome email for user {}: {}", user.getId(), e.getMessage());
            }
        }

        return new EmailVerificationResponse(user.isEmailVerified(), user.getEmailVerifiedAt());
    }

    private boolean isOutsideResendCooldown(User user) {
        return tokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .map(EmailVerificationToken::getCreatedAt)
                .map(lastIssuedAt -> lastIssuedAt.isBefore(Instant.now().minusSeconds(RESEND_COOLDOWN_SECONDS)))
                .orElse(true);
    }

    private String buildVerificationUrl(String rawToken) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return base + "/verify-email?token=" + encodedToken;
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
