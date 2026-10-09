package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

/** {@code emailedTo} is the address a public reply went to, null when none was sent. */
public record MessageView(UUID id, MessageKind kind, String body, MemberRef author, String emailedTo,
        Instant createdAt) {}
