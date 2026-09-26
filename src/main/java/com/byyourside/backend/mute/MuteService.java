package com.byyourside.backend.mute;

import com.byyourside.backend.mute.dto.MutedUserResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

// Fase 9.5: silenciar (mute) es UNILATERAL e invisible para el usuario
// muteado -- a diferencia de Block (bilateral en efecto, ver BlockPolicy),
// mutear a alguien NO modifica Follow/FollowRequest, NO afecta
// ProfileAccessPolicy/PostAccessPolicy (perfil, post detail, posts por
// usuario, comments/support siguen exactamente igual), NO bloquea chat ni
// interaccion, y NO existe forma de que el muted se entere (nunca se
// expone quien te muteo, ver MutedUserResponse/UserController). Solo afecta
// lo que el MUTER ve en superficies agregadas de descubrimiento/contenido
// -- feed, discover, status/presence agregada, disponibilidad/companion --
// filtrado directo en la query de esas superficies, nunca post-filtrado en
// Java (ver PostRepository/StatusRepository/AvailabilityRepository/
// UserService.discoverUsers).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MuteService {

    private final UserMuteRepository userMuteRepository;
    private final UserRepository userRepository;

    // Idempotente: mutear a alguien ya muteado no falla, solo no vuelve a
    // insertar -- mismo criterio que BlockService.blockUser, incluyendo el
    // manejo de la carrera de insercion concurrente.
    @Transactional
    public void muteUser(UUID muterId, UUID targetId) {
        if (muterId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot mute yourself");
        }

        User muter = findByIdOrThrow(muterId);
        User target = findByIdOrThrow(targetId);

        if (!userMuteRepository.existsByMuterIdAndMutedId(muterId, targetId)) {
            try {
                userMuteRepository.save(UserMute.builder().muter(muter).muted(target).build());
            } catch (DataIntegrityViolationException e) {
                // Carrera: otra request concurrente ya inserto el mismo mute
                // entre el chequeo de arriba y este save -- idempotente, no
                // propagamos un 500.
            }
        }
    }

    // Dejar de silenciar es SOLO borrar la fila -- no hay nada que
    // restaurar, porque mutear nunca elimino ni modifico ninguna otra
    // relacion (a diferencia de BlockService.unblockUser, que documenta la
    // misma asimetria pero por una razon distinta: aca simplemente no hay
    // side effects previos que deshacer). Idempotente: si no habia mute,
    // no-op.
    @Transactional
    public void unmuteUser(UUID muterId, UUID targetId) {
        userMuteRepository.findByMuterIdAndMutedId(muterId, targetId)
                .ifPresent(userMuteRepository::delete);
    }

    // Solo lista a quien EL USUARIO ACTUAL muteo -- nunca quien lo muteo a
    // el (ver dto/MutedUserResponse).
    public Page<MutedUserResponse> getMutedUsers(UUID muterId, Pageable pageable) {
        return userMuteRepository.findByMuterIdOrderByCreatedAtDesc(muterId, pageable)
                .map(m -> new MutedUserResponse(
                        m.getMuted().getId(),
                        m.getMuted().getUsername(),
                        m.getMuted().getDisplayName(),
                        m.getMuted().getAvatarUrl(),
                        m.getCreatedAt()
                ));
    }

    private User findByIdOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
