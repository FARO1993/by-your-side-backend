package com.byyourside.backend.availability;

import com.byyourside.backend.availability.dto.AvailabilityResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvailabilityService {

    private static final long EXPIRATION_HOURS = 6;
    private static final int DEFAULT_LIMIT = 10;

    private final AvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;

    @Transactional
    public AvailabilityResponse setAvailability(UserPrincipal principal, CompanionIntent intent) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Solo una disponibilidad activa por vez: reemplaza cualquier
        // declaracion anterior en vez de acumularlas.
        availabilityRepository.deleteByUserId(user.getId());

        Availability availability = availabilityRepository.save(Availability.builder()
                .user(user)
                .intent(intent)
                .expiresAt(Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS))
                .build());

        return toResponse(availability);
    }

    @Transactional
    public void cancelAvailability(UserPrincipal principal) {
        availabilityRepository.deleteByUserId(principal.getId());
    }

    public AvailabilityResponse getMine(UserPrincipal principal) {
        return availabilityRepository
                .findTopByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(principal.getId(), Instant.now())
                .map(this::toResponse)
                .orElse(null);
    }

    public List<AvailabilityResponse> listAvailable(UserPrincipal principal, CompanionIntent intent) {
        return availabilityRepository
                .findRandomAvailable(intent.name(), Instant.now(), principal.getId(), DEFAULT_LIMIT)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AvailabilityResponse toResponse(Availability availability) {
        User user = availability.getUser();
        UserSummary userSummary = new UserSummary(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl()
        );

        return new AvailabilityResponse(
                availability.getId(),
                userSummary,
                availability.getIntent().name(),
                availability.getCreatedAt(),
                availability.getExpiresAt()
        );
    }
}