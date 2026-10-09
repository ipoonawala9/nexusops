package com.nexusops.helpdesk;

import java.util.UUID;

public record AgentView(UUID id, String name, String email) {}
