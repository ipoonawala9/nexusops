package com.nexusops.identity.web;

import com.nexusops.identity.application.ProfileService;
import com.nexusops.identity.application.ProfileService.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final ProfileService profiles;

    MeController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    Profile me() {
        return profiles.me();
    }
}
