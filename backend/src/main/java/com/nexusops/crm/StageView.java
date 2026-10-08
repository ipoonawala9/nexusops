package com.nexusops.crm;

import java.util.UUID;

public record StageView(UUID id, String name, int probability, StageKind kind, int position, long version) {}
