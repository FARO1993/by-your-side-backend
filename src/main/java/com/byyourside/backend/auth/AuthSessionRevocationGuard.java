package com.byyourside.backend.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Bean separado a proposito: REQUIRES_NEW solo funciona si la llamada pasa
// por el proxy de Spring, y una llamada de AuthSessionService a un metodo
// @Transactional DEFINIDO EN SI MISMO ("this.metodo()") nunca pasa por su
// propio proxy (self-invocation) -- la anotacion se ignora en silencio y el
// metodo termina participando de la transaccion del caller. Al vivir en un
// bean distinto, la llamada SI atraviesa el proxy y la nueva transaccion se
// confirma de verdad, independiente de que la transaccion de refresh()
// termine revertida por la excepcion que senaliza el reuse al cliente.
@Component
@RequiredArgsConstructor
public class AuthSessionRevocationGuard {

    private final AuthSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyIndependently(UUID familyId) {
        sessionRepository.revokeFamily(familyId);
    }
}
