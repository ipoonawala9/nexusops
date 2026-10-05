package com.nexusops.identity.application;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    public record Profile(UserView user, TenantSummary tenant, List<String> permissions, List<String> modules) {}

    public record UserView(UUID id, String email, String firstName, String lastName) {}

    private final UserRepository users;
    private final TenantDirectory tenants;
    private final PrincipalStateCache principals;

    ProfileService(UserRepository users, TenantDirectory tenants, PrincipalStateCache principals) {
        this.users = users;
        this.tenants = tenants;
        this.principals = principals;
    }

    @Transactional(readOnly = true)
    public Profile me() {
        CurrentUser current = CurrentUser.require();
        User user = users.findById(current.userId()).orElseThrow(() -> ApiProblem.unauthorized("Authentication is required."));
        var state = principals.get(current.tenantId(), current.userId());
        return new Profile(
                new UserView(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName()),
                tenants.current(),
                state.permissions().stream().sorted().toList(),
                state.modules());
    }
}
