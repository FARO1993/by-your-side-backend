package com.byyourside.backend.auth;

import com.byyourside.backend.auth.dto.AuthResponse;
import com.byyourside.backend.auth.dto.ChangePasswordRequest;
import com.byyourside.backend.auth.dto.ChangePasswordResponse;
import com.byyourside.backend.auth.dto.EmailVerificationResponse;
import com.byyourside.backend.auth.dto.ForgotPasswordRequest;
import com.byyourside.backend.auth.dto.ForgotPasswordResponse;
import com.byyourside.backend.auth.dto.LoginRequest;
import com.byyourside.backend.auth.dto.LogoutRequest;
import com.byyourside.backend.auth.dto.LogoutResponse;
import com.byyourside.backend.auth.dto.RefreshRequest;
import com.byyourside.backend.auth.dto.RefreshResponse;
import com.byyourside.backend.auth.dto.RegisterRequest;
import com.byyourside.backend.auth.dto.ResendVerificationRequest;
import com.byyourside.backend.auth.dto.ResendVerificationResponse;
import com.byyourside.backend.auth.dto.ResetPasswordRequest;
import com.byyourside.backend.auth.dto.ResetPasswordResponse;
import com.byyourside.backend.auth.dto.VerifyEmailRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final PasswordResetService passwordResetService;
    private final ChangePasswordService changePasswordService;
    private final AuthSessionService authSessionService;

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

    // Respuesta siempre generica a proposito -- no distingue email inexistente
    // de rate-limited (ver PasswordResetService).
    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(passwordResetService.forgotPassword(request.email()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(passwordResetService.resetPassword(request.token(), request.newPassword()));
    }

    // Unico endpoint de /api/auth que requiere JWT -- ver SecurityConfig. La
    // identidad sale exclusivamente de `principal` (resuelto por el filtro
    // JWT), nunca de un campo del body: no hay forma de pedir el cambio de
    // contrasena de otro usuario a traves de este request.
    @PostMapping("/change-password")
    public ResponseEntity<ChangePasswordResponse> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(changePasswordService.changePassword(
                principal, request.currentPassword(), request.newPassword()));
    }

    // Publico a proposito: se usa precisamente cuando el access token ya
    // expiro, asi que no puede requerir uno valido. La sesion se identifica
    // exclusivamente por el refresh token del body, nunca por un JWT.
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authSessionService.refresh(request.refreshToken()));
    }

    // Publico a proposito, mismo criterio que /refresh: identificado
    // exclusivamente por el refresh token, no requiere (ni chequea) un JWT
    // vigente -- se puede cerrar sesion incluso con el access token ya
    // vencido, mientras se conserve el refresh token.
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(@Valid @RequestBody LogoutRequest request) {
        return ResponseEntity.ok(authSessionService.logout(request.refreshToken()));
    }
}