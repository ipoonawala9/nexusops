package com.nexusops.crm;

import java.util.UUID;

public record StageRef(UUID id, String name, StageKind kind, int probability) {}
