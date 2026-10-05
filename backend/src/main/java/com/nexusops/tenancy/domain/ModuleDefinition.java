package com.nexusops.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Global, read-only module catalog (V1__tenancy.sql). */
@Entity
@Immutable
@Table(name = "modules")
public class ModuleDefinition {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    protected ModuleDefinition() {}

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
