package com.nexusops.authorization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

final class RoleDtos {

    private RoleDtos() {}

    record CreateRoleRequest(@NotBlank @Size(max = 60) String name, @Size(max = 255) String description,
            @NotNull Set<@NotBlank String> permissions) {}

    record UpdateRoleRequest(@Size(max = 60) String name, @Size(max = 255) String description) {}

    record PermissionsRequest(@NotNull Set<@NotBlank String> permissions) {}
}
