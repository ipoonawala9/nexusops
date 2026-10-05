package com.nexusops.platform.security;

import com.nexusops.platform.domain.PlatformRole;
import java.util.UUID;

/** The authenticated platform operator, set as the authentication's details by PlatformPrincipalFilter. */
public record PlatformActor(UUID id, String email, PlatformRole role) {}
