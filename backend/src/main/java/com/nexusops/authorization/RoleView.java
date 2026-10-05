package com.nexusops.authorization;

import java.util.List;
import java.util.UUID;

public record RoleView(UUID id, String name, String description, boolean system, List<String> permissions) {}
