package com.nexusops.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Global, read-only permission catalog (seeded by V3__rbac.sql). */
@Entity
@Immutable
@Table(name = "permissions")
public class Permission {

    @Id
    private String code;

    @Column(name = "module_code")
    private String moduleCode;

    @Column(nullable = false)
    private String description;

    protected Permission() {}

    public String getCode() {
        return code;
    }

    public String getModuleCode() {
        return moduleCode;
    }
}
