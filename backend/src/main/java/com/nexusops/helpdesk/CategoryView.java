package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

public record CategoryView(UUID id, String name, String description, MemberRef defaultAssignee, int position,
        Instant archivedAt, long version) {}
