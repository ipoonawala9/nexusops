package com.nexusops.authorization;

import java.util.Set;

public record CreateRoleCommand(String name, String description, Set<String> permissions) {}
