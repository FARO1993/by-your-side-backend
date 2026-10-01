package com.byyourside.backend.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResendEmailService implements EmailService {

    private final Resend resend;

    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Override
    public void sendVerificationEmail(String toEmail, String displayName, String verificationUrl) {
        String subject = "Verificá tu cuenta de ByYourSide";
        String html = """
                <p>%s</p>
                <p>Gracias por sumarte a ByYourSide. Para activar tu cuenta, confirmá tu
                email haciendo clic en el siguiente enlace:</p>
                <p><a href="%s">Verificar mi email</a></p>
                <p>Este enlace vence en 24 horas.</p>
                <p>Si vos no creaste esta cuenta, podés ignorar este correo.</p>
                """.formatted(greeting(displayName), verificationUrl);

        send(toEmail, subject, html);
    }

    @Override
    public void sendWelcomeEmail(String toEmail, String displayName) {
        String subject = "¡Bienvenido/a a ByYourSide!";
        String html = """
                <p>%s</p>
                <p>Tu email quedó verificado. Ya podés usar ByYourSide con tu cuenta.</p>
                <p>Gracias por sumarte -- no tenés que enfrentar nada solo/a.</p>
                """.formatted(greeting(displayName));

        send(toEmail, subject, html);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String displayName, String resetUrl) {
        String subject = "Recuperá tu contraseña de ByYourSide";
        String html = """
                <p>%s</p>
                <p>Recibimos una solicitud para restablecer la contraseña de tu cuenta.
                Si fuiste vos, hacé clic en el siguiente enlace:</p>
                <p><a href="%s">Restablecer mi contraseña</a></p>
                <p>Este enlace vence en 30 minutos.</p>
                <p>Si vos no pediste este cambio, podés ignorar este correo -- tu
                contraseña actual sigue funcionando sin cambios.</p>
                """.formatted(greeting(displayName), resetUrl);

        send(toEmail, subject, html);
    }

    @Override
    public void sendPasswordChangedEmail(String toEmail, String displayName) {
        String subject = "Tu contraseña de ByYourSide fue cambiada";
        String html = """
                <p>%s</p>
                <p>Tu contraseña se cambió correctamente. Si vos hiciste este cambio, no
                necesitás hacer nada más.</p>
                <p>Si vos NO pediste este cambio, contactanos lo antes posible.</p>
                """.formatted(greeting(displayName));

        send(toEmail, subject, html);
    }

    private void send(String toEmail, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            // Sin RESEND_API_KEY configurada (default en dev/test, igual que
            // CLOUDINARY_API_KEY): no hay proveedor real al que llamar, no es
            // un error. Evita llamadas de red innecesarias en toda la suite
            // de tests, que no mockea este service.
            log.debug("Resend API key not configured; skipping email send (subject='{}')", subject);
            return;
        }

        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(fromAddress)
                .to(toEmail)
                .subject(subject)
                .html(html)
                .build();

        try {
            resend.emails().send(params);
        } catch (ResendException e) {
            // Nunca logueamos el contenido del email, la API key ni el
            // verification token -- solo el motivo del fallo.
            log.error("Failed to send email via Resend (subject='{}'): {}", subject, e.getMessage());
            throw new EmailDeliveryException("Failed to send email via Resend", e);
        }
    }

    private String greeting(String displayName) {
        return (displayName == null || displayName.isBlank())
                ? "¡Hola!"
                : "Hola " + escape(displayName) + ",";
    }

    // Escape minimo: el HTML del email interpola displayName, que es texto
    // libre elegido por el propio usuario.
    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
