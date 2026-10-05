package com.nexusops.identity.application;

import java.time.Instant;

public record InvitationPreview(String workspace, String workspaceName, String email, String roleName, Instant expiresAt) {}
