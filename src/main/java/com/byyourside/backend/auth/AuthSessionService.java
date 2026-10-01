package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.LogoutResponse;
import com.byyourside.backend.auth.dto.RefreshResponse;
import com.byyourside.backend.security.JwtService;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.UUID;

// Refresh token rotation con deteccion de reuse (Fase 1.5). Una "sesion" (un
// login en un dispositivo dado) es una familia de filas AuthSession que
// comparten familyId -- cada rotacion crea una fila nueva en la misma
// familia, nunca sobreescribe el hash de la fila existente, para poder
// reconocer si una generacion ya rotada vuelve a presentarse mas tarde
// (reuse). Mismo patron de hash SHA-256 que EmailVerificationService/
// PasswordResetService.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuthSessionService {

    private static final int TOKEN_BYTES = 32;

    private final AuthSessionRepository sessionRepository;
    private final JwtService jwtService;
    private final AuthSessionRevocationGuard revocationGuard;

    @Value("${app.session.refresh-expiration-ms}")
    private long refreshExpirationMs;

    // Login/register: arranca una familia NUEVA -- nunca reutiliza la de otro
    // dispositivo ya conectado. Devuelve el refresh token en texto plano
    // (unica vez que existe fuera de la memoria del proceso); solo su hash
    // queda persistido.
    @Transactional
    public String createSession(User user) {
        return createSessionInFamily(user, UUID.randomUUID());
    }

    @Transactional
    public RefreshResponse refresh(String rawToken) {
        AuthSession session = sessionRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refresh token"));

        if (session.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh token has been revoked");
        }

        if (session.isRotated()) {
            // Esta generacion ya fue intercambiada por una nueva ANTES de
            // esta request (no es una carrera concurrente, es una
            // reaparicion real de un token viejo) -- firma clasica de un
            // token copiado/robado. Se revoca la familia entera en su PROPIA
            // transaccion (ver AuthSessionRevocationGuard) porque esta
            // request va a terminar en excepcion, y una excepcion sin
            // atrapar revierte la transaccion de este metodo -- sin esto, la
            // revocacion se perderia junto con el resto del rollback.
            revocationGuard.revokeFamilyIndependently(session.getFamilyId());
            log.warn("Refresh token reuse detected for user {}, family {}", session.getUser().getId(), session.getFamilyId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh token has already been used");
        }

        if (session.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh token has expired");
        }

        // Claim atomico: si esto devuelve 0, alguien mas (una request
        // concurrente sobre este mismo token) ya lo roto entre nuestro
        // SELECT de arriba y este UPDATE. A diferencia del caso de reuse de
        // arriba, esto NO revoca la familia -- es una carrera benigna entre
        // requests legitimas (ej. doble click, reintento de red), no
        // evidencia de un token copiado. Ver AuthSessionRepository.
        int claimed = sessionRepository.claimForRotation(session.getId());
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh token has already been used");
        }

        User user = session.getUser();
        String newRawToken = createSessionInFamily(user, session.getFamilyId());
        String accessToken = jwtService.generateToken(new UserPrincipal(user));

        return new RefreshResponse(accessToken, newRawToken, "Bearer", jwtService.getAccessTokenExpirationSeconds());
    }

    // Idempotente y sin distinguir casos en la respuesta a proposito (mismo
    // criterio anti-enumeration que forgot-password): token inexistente,
    // expirado, ya rotado o ya revocado terminan en el mismo mensaje
    // generico. No requiere JWT -- la sesion se identifica exclusivamente
    // por el refresh token, nunca por identidad enviada por el cliente.
    @Transactional
    public LogoutResponse logout(String rawToken) {
        sessionRepository.findByTokenHash(hash(rawToken))
                .ifPresent(session -> sessionRepository.revokeFamily(session.getFamilyId()));

        return new LogoutResponse("Logged out successfully.");
    }

    // Usado por ChangePasswordService y PasswordResetService: por decision de
    // producto, cambiar o resetear la contrasena cierra TODAS las sesiones
    // del usuario (todas las familias), no solo la actual. Se llama dentro de
    // la misma transaccion que persiste la contrasena nueva -- si algo
    // fallara, ambas cosas se revierten juntas. El envio del email de
    // confirmacion sigue siendo un intento aparte, nunca revierte esto.
    @Transactional
    public void revokeAllForUser(UUID userId) {
        sessionRepository.revokeAllForUser(userId);
    }

    private String createSessionInFamily(User user, UUID familyId) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();

        AuthSession session = AuthSession.builder()
                .user(user)
                .familyId(familyId)
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(refreshExpirationMs, ChronoUnit.MILLIS))
                .lastUsedAt(now)
                .build();

        sessionRepository.save(session);
        return rawToken;
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
