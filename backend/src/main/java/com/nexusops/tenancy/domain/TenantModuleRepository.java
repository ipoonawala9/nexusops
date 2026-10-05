package com.nexusops.tenancy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantModuleRepository extends JpaRepository<TenantModule, UUID> {

    /** Tenant-scoped automatically by @TenantId and RLS. */
    @Query("select m.moduleCode from TenantModule m where m.enabled = true order by m.moduleCode")
    List<String> findEnabledCodes();
}
