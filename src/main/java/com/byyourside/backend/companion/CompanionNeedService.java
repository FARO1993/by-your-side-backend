package com.byyourside.backend.companion;

import com.byyourside.backend.companion.dto.CompanionNeedResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanionNeedService {

    // Maximo de intentos ante una carrera de UNIQUE(user_id) (ver V14 y
    // CompanionNeedWriter). Dos PUT concurrentes alcanzan con un solo
    // reintento -- el perdedor de la carrera ya no compite con nadie en su
    // segundo intento; el margen extra es solo defensivo para mas de dos
    // requests simultaneas.
    private static final int MAX_ATTEMPTS = 3;

    private final CompanionNeedRepository companionNeedRepository;
    private final CompanionNeedWriter companionNeedWriter;
    private final UserRepository userRepository;

    // Solo un Need activo por vez: reemplaza cualquier declaracion anterior
    // en vez de acumularlas -- mismo criterio que Availability, pero con la
    // exclusividad garantizada a nivel DB (UNIQUE(user_id), ver V14), no
    // solo por el orden delete-then-insert del service.
    public CompanionNeedResponse setNeed(UserPrincipal principal, NeedType type) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        CompanionNeed need = replaceWithRetry(user, type, MAX_ATTEMPTS);
        return toResponse(need);
    }

    // La DB es la ultima defensa contra dos PUT concurrentes:
    // CompanionNeedWriter.replace corre en su propia transaccion
    // (REQUIRES_NEW) y puede fallar con DataIntegrityViolationException si
    // otro PUT concurrente ya inserto su fila entre nuestro DELETE y
    // nuestro INSERT. Reintentamos: para entonces la fila del ganador ya
    // esta commiteada, asi que nuestro proximo intento la borra y la
    // reemplaza sin conflicto -- nunca quedan dos filas, y nunca se pierde
    // silenciosamente la intencion de esta request con un 500.
    private CompanionNeed replaceWithRetry(User user, NeedType type, int attemptsLeft) {
        try {
            return companionNeedWriter.replace(user, type);
        } catch (DataIntegrityViolationException e) {
            if (attemptsLeft <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Could not save your companion need, please retry");
            }
            return replaceWithRetry(user, type, attemptsLeft - 1);
        }
    }

    @Transactional
    public void cancelNeed(UserPrincipal principal) {
        companionNeedRepository.deleteByUserId(principal.getId());
    }

    // Ausencia genuina de dato = 200 con body null, nunca 404 -- mismo
    // criterio que AvailabilityService.getMine / StatusService.getCurrentStatus.
    public CompanionNeedResponse getMine(UserPrincipal principal) {
        return companionNeedRepository
                .findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(principal.getId(), Instant.now())
                .map(this::toResponse)
                .orElse(null);
    }

    private CompanionNeedResponse toResponse(CompanionNeed need) {
        return new CompanionNeedResponse(
                need.getId(),
                need.getType().name(),
                need.getCreatedAt(),
                need.getExpiresAt()
        );
    }
}
