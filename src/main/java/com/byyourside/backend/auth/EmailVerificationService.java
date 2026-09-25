package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.EmailVerificationResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    // Genera y persiste un nuevo token para el usuario dado, devuelve el
    // valor real (nunca se guarda en la base, solo su hash). Por ahora, sin
    // envio real de email (Fase 1.2), se loguea para poder probarlo a mano.
    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();

        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build();

        tokenRepository.save(token);

        // TODO(Fase 1.2): reemplazar este log por el envio real del email de
        // verificacion con este token/link. Hasta entonces es la unica forma
        // de obtenerlo (no se expone en ninguna respuesta HTTP a proposito).
        log.info("Email verification token issued for user {}: {}", user.getId(), rawToken);

        return rawToken;
    }

    @Transactional
    public EmailVerificationResponse verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token"));

        if (token.isUsed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification token has already been used");
        }

        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has expired");
        }

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);

        User user = token.getUser();

        // Idempotente: si el usuario ya estaba verificado (por otro token
        // valido, ej. reenviado), no se pisa la fecha original de verificacion.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
        }

        return new EmailVerificationResponse(user.isEmailVerified(), user.getEmailVerifiedAt());
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
