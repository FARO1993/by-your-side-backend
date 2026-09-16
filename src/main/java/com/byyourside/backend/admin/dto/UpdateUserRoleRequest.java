package com.byyourside.backend.admin.dto;

import com.byyourside.backend.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(

        @NotNull
        UserRole role
) {
}