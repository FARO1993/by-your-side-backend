package com.byyourside.backend.email;

// Abstraccion propia sobre el proveedor de email transaccional -- nada fuera
// de este paquete conoce Resend directamente.
public interface EmailService {

    void sendVerificationEmail(String toEmail, String displayName, String verificationUrl);

    void sendWelcomeEmail(String toEmail, String displayName);

    void sendPasswordResetEmail(String toEmail, String displayName, String resetUrl);

    void sendPasswordChangedEmail(String toEmail, String displayName);
}
