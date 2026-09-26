package com.byyourside.backend.mute;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserMuteRepository extends JpaRepository<UserMute, UUID> {

    boolean existsByMuterIdAndMutedId(UUID muterId, UUID mutedId);

    Optional<UserMute> findByMuterIdAndMutedId(UUID muterId, UUID mutedId);

    Page<UserMute> findByMuterIdOrderByCreatedAtDesc(UUID muterId, Pageable pageable);

    // Batch para discover, mismo patron que
    // UserBlockRepository.findBlockedIdsByBlocker -- pero UNA sola direccion
    // a proposito (mute nunca oculta nada del lado del muted): no existe un
    // equivalente a findBlockerIdsByBlocked aca.
    @Query("SELECT m.muted.id FROM UserMute m WHERE m.muter.id = :muterId")
    Set<UUID> findMutedIdsByMuter(@Param("muterId") UUID muterId);
}
