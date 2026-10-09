package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Priority;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findByPriority(Priority priority);
}
