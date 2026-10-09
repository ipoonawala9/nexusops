package com.nexusops.helpdesk;

import java.util.UUID;

public record CategoryCommand(String name, String description, UUID defaultAssigneeId) {}
