package com.nexusops.identity;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public identity API: members of the current workspace, for other modules (task assignees, authors). */
@Service
public class Members {

    public record Member(UUID id, String name, String email, boolean active) {}

    private final UserRepository users;

    Members(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<Member> findActive(UUID id) {
        TenantContext.requireTenantId();
        return users.findById(id).filter(u -> u.getStatus() == UserStatus.ACTIVE).map(Members::member);
    }

    /** Any status, for display names; unknown ids (or other tenants') are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, Member> findAll(Collection<UUID> ids) {
        TenantContext.requireTenantId();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(User::getId, Members::member));
    }

    @Transactional(readOnly = true)
    public List<Member> searchActive(String q, int limit) {
        TenantContext.requireTenantId();
        Specification<User> spec = (root, query, cb) -> cb.equal(root.get("status"), UserStatus.ACTIVE);
        String text = Text.optional(q, 100, "q");
        if (text != null) {
            String like = Text.containsPattern(text);
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("firstName")), like, '\\'),
                    cb.like(cb.lower(root.get("lastName")), like, '\\'), cb.like(root.get("email"), like, '\\')));
        }
        return users.findAll(spec, PageRequest.of(0, limit, Sort.by("firstName", "lastName", "id")))
                .map(Members::member).getContent();
    }

    private static Member member(User user) {
        return new Member(user.getId(), (user.getFirstName() + " " + user.getLastName()).strip(), user.getEmail(),
                user.getStatus() == UserStatus.ACTIVE);
    }
}
