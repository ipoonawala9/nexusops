package com.nexusops.collaboration.infrastructure;

import com.nexusops.collaboration.DocumentStorage;
import com.nexusops.shared.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stores contents in the RLS-protected document_contents table, in the same transaction as the metadata. */
@Component
class DatabaseDocumentStorage implements DocumentStorage {

    private final JdbcTemplate jdbc;

    DatabaseDocumentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID documentId, byte[] content) {
        jdbc.update("insert into document_contents (document_id, tenant_id, content) values (?, ?, ?)", documentId,
                TenantContext.requireTenantId(), content);
    }

    @Override
    public Optional<byte[]> load(UUID documentId) {
        List<byte[]> rows = jdbc.query("select content from document_contents where document_id = ?",
                (rs, i) -> rs.getBytes(1), documentId);
        return rows.stream().findFirst();
    }

    @Override
    public void delete(UUID documentId) {
        jdbc.update("delete from document_contents where document_id = ?", documentId);
    }
}
