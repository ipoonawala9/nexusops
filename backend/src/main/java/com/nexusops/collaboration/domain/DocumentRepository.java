package com.nexusops.collaboration.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(String subjectType, UUID subjectId);

    @Query("select coalesce(sum(d.sizeBytes), 0) from Document d")
    long totalBytes();
}
