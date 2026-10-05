package com.nexusops.identity.web;

import com.nexusops.identity.application.InvitationService;
import com.nexusops.identity.application.InvitationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {

    record InviteRequest(@NotBlank @Size(max = 254) String email, @NotNull UUID roleId) {}

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity.user.invite')")
    InvitationView invite(@Valid @RequestBody InviteRequest request) {
        return invitations.invite(request.email(), request.roleId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity.user.read')")
    List<InvitationView> list() {
        return invitations.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('identity.user.invite')")
    void revoke(@PathVariable UUID id) {
        invitations.revoke(id);
    }
}
