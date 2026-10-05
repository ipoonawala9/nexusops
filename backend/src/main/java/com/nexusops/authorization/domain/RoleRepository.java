package com.nexusops.authorization.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByNameIgnoreCase(String name);

    java.util.Optional<Role> findByNameAndSystemTrue(String name);

    java.util.List<Role> findAllByOrderBySystemDescNameAsc();

    /** user_roles rows are RLS-protected: only this tenant's assignments are counted. */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true,
            value = "select count(*) from user_roles where role_id = :roleId")
    long countAssignments(@org.springframework.data.repository.query.Param("roleId") UUID roleId);
}
