package com.byyourside.backend.email;

// Unchecked a proposito: los callers (EmailVerificationService) deciden si
// tolerar el fallo (caso actual) sin forzar un catch en toda la cadena.
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
