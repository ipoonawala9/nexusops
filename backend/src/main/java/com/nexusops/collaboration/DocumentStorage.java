package com.nexusops.collaboration;

import java.util.Optional;
import java.util.UUID;

/**
 * Where document bytes live (ADR-0009). Calls run inside the caller's transaction and tenant context; an object-store
 * adapter must key objects by tenant and defer deletes until after commit.
 */
public interface DocumentStorage {

    void store(UUID documentId, byte[] content);

    Optional<byte[]> load(UUID documentId);

    void delete(UUID documentId);
}
