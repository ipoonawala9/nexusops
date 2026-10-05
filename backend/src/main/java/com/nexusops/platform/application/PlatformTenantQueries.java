package com.nexusops.platform.application;

import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Cross-tenant workspace read model for the platform console (decision 4). Runs only inside PlatformAccess, whose
 * flag makes the platform_read SELECT policies on users/roles/user_roles apply; nothing here can write tenant data.
 */
@Service
public class PlatformTenantQueries {

    static final String NOT_FOUND = "Workspace not found.";

    private static final String SELECT = """
            select t.id, t.slug, t.name, t.status, t.plan_code, t.created_at,
                   (select count(*) from users u where u.tenant_id = t.id and u.status = 'ACTIVE') as active_users,
                   array(select u.email from users u
                           join user_roles ur on ur.user_id = u.id
                           join roles r on r.id = ur.role_id
                          where u.tenant_id = t.id and u.status = 'ACTIVE' and r.system and r.name = 'TENANT_OWNER'
                          order by u.email) as owner_emails
            from tenants t""";

    private static final RowMapper<PlatformTenantView> ROW = (rs, n) -> new PlatformTenantView(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("plan_code"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("active_users"),
            Arrays.asList((String[]) rs.getArray("owner_emails").getArray()));

    private final PlatformAccess access;
    private final JdbcTemplate jdbc;

    PlatformTenantQueries(PlatformAccess access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public PageResponse<PlatformTenantView> list(String status, String q, Integer page, Integer size) {
        Pageable pageable = Paging.of(page, size);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and t.status = ?");
            args.add(parseStatus(status).name());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
            where.append(" and (lower(t.slug) like ? escape '\\' or lower(t.name) like ? escape '\\')");
            args.add(like);
            args.add(like);
        }
        return access.read(() -> {
            Long total = jdbc.queryForObject("select count(*) from tenants t" + where, Long.class, args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(pageable.getPageSize());
            pageArgs.add(pageable.getOffset());
            List<PlatformTenantView> items = jdbc.query(
                    SELECT + where + " order by t.created_at desc, t.id limit ? offset ?", ROW, pageArgs.toArray());
            return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
        });
    }

    public PlatformTenantView get(UUID id) {
        return access.read(() -> jdbc.query(SELECT + " where t.id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> ApiProblem.notFound(NOT_FOUND)));
    }

    private static TenantStatus parseStatus(String raw) {
        try {
            return TenantStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("status", "Status must be PENDING_VERIFICATION, ACTIVE or SUSPENDED.");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
