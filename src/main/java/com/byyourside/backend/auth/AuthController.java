package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.AuthResponse;
import com.byyourside.backend.auth.dto.EmailVerificationResponse;
import com.byyourside.backend.auth.dto.LoginRequest;
import com.byyourside.backend.auth.dto.RegisterRequest;
import com.byyourside.backend.auth.dto.ResendVerificationRequest;
import com.byyourside.backend.auth.dto.ResendVerificationResponse;
import com.byyourside.backend.auth.dto.VerifyEmailRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<EmailVerificationResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(emailVerificationService.verify(request.token()));
    }

    // Respuesta siempre generica a proposito -- no distingue email
    // inexistente, ya verificado, o rate-limited (ver EmailVerificationService).
    @PostMapping("/resend-verification")
    public ResponseEntity<ResendVerificationResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resendVerification(request.email());
        return ResponseEntity.ok(new ResendVerificationResponse(
                "If an account with that email needs verification, we've sent a new email."));
    }
}