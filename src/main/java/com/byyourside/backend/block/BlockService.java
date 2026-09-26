package com.byyourside.backend.block;

import com.byyourside.backend.block.dto.BlockedUserResponse;
import com.byyourside.backend.follow.FollowRepository;
import com.byyourside.backend.follow.FollowRequestRepository;
import com.byyourside.backend.follow.FollowRequestStatus;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final FollowRequestRepository followRequestRepository;

    // Metodo centralizado unico para bloquear + toda la limpieza asociada,
    // en una sola transaccion (Fase 9.4 § "centralizar toda la operacion").
    // Idempotente: bloquear a alguien ya bloqueado no falla (ni 409), solo
    // no vuelve a insertar -- pero SI vuelve a correr la limpieza, que a su
    // vez tambien es idempotente (borrar un Follow/FollowRequest que ya no
    // esta ahi es un no-op).
    @Transactional
    public void blockUser(UUID blockerId, UUID targetId) {
        if (blockerId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot block yourself");
        }

        User blocker = findByIdOrThrow(blockerId);
        User target = findByIdOrThrow(targetId);

        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, targetId)) {
            try {
                userBlockRepository.save(UserBlock.builder().blocker(blocker).blocked(target).build());
            } catch (DataIntegrityViolationException e) {
                // Carrera: otra request concurrente ya inserto el mismo bloqueo
                // entre el chequeo de arriba y este save -- idempotente, no
                // propagamos un 500.
            }
        }

        // Limpieza bilateral: borra cualquier Follow real en AMBAS direcciones
        // (A->B y B->A) -- un bloqueo corta la relacion completa, sin importar
        // quien seguia a quien.
        followRepository.findByFollowerIdAndFollowingId(blockerId, targetId).ifPresent(followRepository::delete);
        followRepository.findByFollowerIdAndFollowingId(targetId, blockerId).ifPresent(followRepository::delete);

        // Cancela (no borra) cualquier FollowRequest PENDING en ambas
        // direcciones -- se conserva como CANCELLED, mismo criterio de
        // historial minimo que el resto de FollowRequest; el claim atomico
        // evita pisar una request que otra transaccion concurrente (accept/
        // reject/cancel del usuario) ya resolvio.
        followRequestRepository.findByRequesterIdAndTargetIdAndStatus(blockerId, targetId, FollowRequestStatus.PENDING)
                .ifPresent(r -> followRequestRepository.claimCancel(r.getId()));
        followRequestRepository.findByRequesterIdAndTargetIdAndStatus(targetId, blockerId, FollowRequestStatus.PENDING)
                .ifPresent(r -> followRequestRepository.claimCancel(r.getId()));
    }

    // Desbloquear es SOLO borrar la fila -- a proposito no recrea el Follow,
    // no reactiva la FollowRequest cancelada, no restaura conversaciones ni
    // disponibilidad. Solo el propio blocker puede desbloquear (el path es
    // siempre principal.getId() como blockerId, nunca body). Idempotente: si
    // no habia bloqueo, no-op.
    @Transactional
    public void unblockUser(UUID blockerId, UUID targetId) {
        userBlockRepository.findByBlockerIdAndBlockedId(blockerId, targetId)
                .ifPresent(userBlockRepository::delete);
    }

    // Solo lista a quien EL USUARIO ACTUAL bloqueo -- nunca quien lo bloqueo
    // a el (ver dto/BlockedUserResponse).
    public Page<BlockedUserResponse> getBlockedUsers(UUID blockerId, Pageable pageable) {
        return userBlockRepository.findByBlockerIdOrderByCreatedAtDesc(blockerId, pageable)
                .map(b -> new BlockedUserResponse(
                        b.getBlocked().getId(),
                        b.getBlocked().getUsername(),
                        b.getBlocked().getDisplayName(),
                        b.getBlocked().getAvatarUrl(),
                        b.getCreatedAt()
                ));
    }

    private User findByIdOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
