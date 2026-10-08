package com.nexusops.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    /** FOR SHARE, held to commit: stock writers use it so an archive (FOR UPDATE) waits for them. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> findByIdForShare(@Param("id") UUID id);

    /** FOR UPDATE: archiving takes it, so it waits for in-flight stock writes and later writers see archived_at. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByCodeKey(String codeKey);

    boolean existsByCodeKeyAndIdNot(String codeKey, UUID id);

    long countByArchivedAtIsNull();

    List<Warehouse> findByArchivedAtIsNullOrderByCodeKeyAsc();

    List<Warehouse> findByArchivedAtIsNotNullOrderByCodeKeyAsc();
}
