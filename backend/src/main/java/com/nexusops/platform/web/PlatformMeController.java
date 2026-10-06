package com.nexusops.platform.web;

import com.nexusops.platform.security.CurrentPlatformActor;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformMeController {

    record PlatformMeResponse(UUID id, String email, String role, List<String> permissions) {}

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('platform.tenant.read')")
    PlatformMeResponse me() {
        var actor = CurrentPlatformActor.require();
        return new PlatformMeResponse(actor.id(), actor.email(), actor.role().name(),
                actor.role().authorities().stream().sorted().toList());
    }
}
