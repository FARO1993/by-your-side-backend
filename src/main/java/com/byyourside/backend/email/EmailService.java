package com.byyourside.backend.email;

// Abstraccion propia sobre el proveedor de email transaccional -- nada fuera
// de este paquete conoce Resend directamente (mismo patron que
// ImageStorageService/CloudinaryImageStorageService para Cloudinary).
public interface EmailService {

    void sendVerificationEmail(String toEmail, String displayName, String verificationUrl);

    void sendWelcomeEmail(String toEmail, String displayName);
}
