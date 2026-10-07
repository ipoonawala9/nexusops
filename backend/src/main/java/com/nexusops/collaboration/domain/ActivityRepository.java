package com.nexusops.collaboration.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    Page<Activity> findBySubjectTypeAndSubjectId(String subjectType, UUID subjectId, Pageable pageable);
}
