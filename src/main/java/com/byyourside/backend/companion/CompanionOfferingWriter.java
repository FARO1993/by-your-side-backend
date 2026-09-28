package com.byyourside.backend.companion;

import com.byyourside.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Bean separado a proposito, mismo criterio exacto que CompanionNeedWriter
// (B4B.1) y AuthSessionRevocationGuard: REQUIRES_NEW solo funciona si la
// llamada atraviesa el proxy de Spring -- self-invocation lo ignoraria en
// silencio. No se extrajo una abstraccion generica compartida con
// CompanionNeedWriter a proposito: el unico codigo en comun seria el
// esqueleto delete+flush+saveAndFlush, y generalizarlo sobre el tipo de
// entidad/repositorio agregaria genéricos e indirección por ahorrar ~10
// lineas duplicadas -- peor legibilidad que mantener los dos writers
// separados y explicitos.
@Component
@RequiredArgsConstructor
public class CompanionOfferingWriter {

    private static final long EXPIRATION_HOURS = 6;

    private final CompanionOfferingRepository companionOfferingRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompanionOffering replace(User user, OfferingType type) {
        // El DELETE se flushea antes del INSERT a proposito -- mismo
        // motivo que CompanionNeedWriter.replace: el orden de flush por
        // defecto de Hibernate ejecuta inserts antes que deletes dentro de
        // un mismo flush, lo que violaria UNIQUE(user_id) contra la fila
        // vieja (incluso una expirada, ver V15) todavia no borrada.
        companionOfferingRepository.deleteByUserId(user.getId());
        companionOfferingRepository.flush();

        return companionOfferingRepository.saveAndFlush(CompanionOffering.builder()
                .user(user)
                .type(type)
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build());
    }
}
