package com.byyourside.backend.admin;

import com.byyourside.backend.admin.dto.UpdateUserRoleRequest;
import com.byyourside.backend.user.UserService;
import com.byyourside.backend.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    @PatchMapping("/{userId}/role")
    public ResponseEntity<UserResponse> updateRole(@PathVariable UUID userId,
                                                   @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(userService.updateRole(userId, request.role()));
    }
}