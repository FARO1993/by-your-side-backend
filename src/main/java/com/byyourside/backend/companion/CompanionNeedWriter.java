package com.byyourside.backend.companion;

import com.byyourside.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Bean separado a proposito, mismo criterio que AuthSessionRevocationGuard:
// REQUIRES_NEW solo funciona si la llamada atraviesa el proxy de Spring --
// una llamada de CompanionNeedService a un metodo @Transactional definido
// en si mismo (self-invocation) ignoraria la anotacion en silencio y
// terminaria participando de la transaccion del caller. Viviendo en un
// bean distinto, cada intento de reemplazo corre en SU PROPIA transaccion:
// si la carrera de UNIQUE(user_id) (ver V14) la revierte, la transaccion
// del caller (que solo hizo la lectura de User) queda intacta y puede
// reintentar con una conexion sana -- evita el problema de Postgres de
// abortar el resto de la transaccion ante cualquier error de SQL.
@Component
@RequiredArgsConstructor
public class CompanionNeedWriter {

    private static final long EXPIRATION_HOURS = 2;

    private final CompanionNeedRepository companionNeedRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompanionNeed replace(User user, NeedType type) {
        // El DELETE se flushea antes del INSERT a proposito: el orden de
        // flush por defecto de Hibernate ejecuta inserts antes que deletes
        // dentro de un mismo flush, lo que violaria UNIQUE(user_id) contra
        // la fila vieja todavia no borrada si dejaramos que ambos viajen
        // juntos en un solo flush implicito al final de la transaccion.
        companionNeedRepository.deleteByUserId(user.getId());
        companionNeedRepository.flush();

        return companionNeedRepository.saveAndFlush(CompanionNeed.builder()
                .user(user)
                .type(type)
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build());
    }
}
