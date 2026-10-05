package com.nexusops.tenancy.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleDefinitionRepository extends JpaRepository<ModuleDefinition, String> {

    List<ModuleDefinition> findAllByOrderByCodeAsc();
}
