package com.byyourside.backend.user;

import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.dto.UpdateProfileRequest;
import com.byyourside.backend.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getCurrentUser(UserPrincipal principal) {
        User user = findByIdOrThrow(principal.getId());
        return toResponse(user);
    }

    public UserResponse updateProfile(UserPrincipal principal, UpdateProfileRequest request) {
        User user = findByIdOrThrow(principal.getId());

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateRole(UUID targetUserId, UserRole newRole) {
        User target = findByIdOrThrow(targetUserId);

        // Evita que la cola de moderacion se quede sin nadie que pueda resolverla.
        if (target.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN) {
            if (userRepository.countByRole(UserRole.ADMIN) <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last remaining admin");
            }
        }

        target.setRole(newRole);
        target = userRepository.save(target);
        return toResponse(target);
    }


    private User findByIdOrThrow(java.util.UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }
}