package com.nexusops.collaboration;

import java.util.UUID;

/** A record that activities, tasks and documents attach to. {@code label} is null when the caller can't read it. */
public record SubjectRef(String type, UUID id, String label, boolean archived) {}
