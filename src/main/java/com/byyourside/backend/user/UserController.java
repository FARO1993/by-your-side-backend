package com.byyourside.backend.user;

import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.dto.UpdateProfileRequest;
import com.byyourside.backend.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                      @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(principal, request));
    }
}