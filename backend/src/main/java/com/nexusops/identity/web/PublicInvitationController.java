package com.nexusops.identity.web;

import com.nexusops.identity.application.AcceptedInvitation;
import com.nexusops.identity.application.InvitationPreview;
import com.nexusops.identity.application.InvitationService;
import com.nexusops.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public invitation routes — both are listed in PublicEndpoints and rate-limited per IP. */
@RestController
@RequestMapping("/api/v1/invitations")
class PublicInvitationController {

    record AcceptRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName, @NotNull @Size(max = 128) String password) {}

    private final InvitationService invitations;
    private final RateLimits rateLimits;

    PublicInvitationController(InvitationService invitations, RateLimits rateLimits) {
        this.invitations = invitations;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/preview")
    InvitationPreview preview(@RequestParam @Size(max = 200) String token, HttpServletRequest http) {
        rateLimits.checkPublic("invitation-preview", http.getRemoteAddr());
        return invitations.preview(token);
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    AcceptedInvitation accept(@Valid @RequestBody AcceptRequest request, HttpServletRequest http) {
        rateLimits.checkPublic("invitation-accept", http.getRemoteAddr());
        return invitations.accept(request.token(), request.firstName(), request.lastName(), request.password());
    }
}
