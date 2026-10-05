package com.nexusops.authorization;

public record PermissionView(String code, String module, String description, boolean moduleEnabled) {}
