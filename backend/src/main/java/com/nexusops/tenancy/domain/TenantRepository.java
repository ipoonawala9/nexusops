package com.nexusops.tenancy.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    interface PlanLimitsRow {
        String getMaxUsers();

        String getMaxModules();

        String getMaxStorageMb();
    }

    @Query(nativeQuery = true, value = """
            select p.limits->>'maxUsers' as maxUsers, p.limits->>'maxModules' as maxModules,
                   p.limits->>'maxStorageMb' as maxStorageMb
            from plans p join tenants t on t.plan_code = p.code
            where t.id = :tenantId""")
    PlanLimitsRow findPlanLimits(@Param("tenantId") UUID tenantId);
}
