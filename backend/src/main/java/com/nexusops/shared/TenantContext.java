package com.nexusops.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant (and user) the current thread acts for. Bound ONLY from a verified JWT or a
 * server-side lookup (ADR-0002) — never from client-supplied headers or bodies.
 *
 * <p>The tenant is written onto each JDBC connection at checkout (TenantAwareDataSource), so a
 * scope must be opened BEFORE a transaction starts; switching tenant inside a transaction would
 * leave the connection on the old tenant and is refused.
 */
public final class TenantContext {

    private record Binding(UUID tenantId, UUID userId) {}

    /** A bound scope; closing it restores the previous binding. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final String MDC_TENANT = "tenant_id";
    private static final String MDC_USER = "user_id";

    private TenantContext() {}

    public static Optional<UUID> tenantId() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.of(binding.tenantId());
    }

    public static UUID requireTenantId() {
        return tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound to the current thread"));
    }

    public static Optional<UUID> userId() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.userId());
    }

    public static Scope open(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Binding previous = CURRENT.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && (previous == null || !previous.tenantId().equals(tenantId))) {
            throw new IllegalStateException("Cannot bind or switch tenant inside an active transaction; "
                    + "open the TenantContext scope before the transaction starts");
        }
        String previousTenantMdc = MDC.get(MDC_TENANT);
        String previousUserMdc = MDC.get(MDC_USER);

        CURRENT.set(new Binding(tenantId, userId));
        MDC.put(MDC_TENANT, tenantId.toString());
        putOrRemove(MDC_USER, userId == null ? null : userId.toString());

        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            putOrRemove(MDC_TENANT, previousTenantMdc);
            putOrRemove(MDC_USER, previousUserMdc);
        };
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        try (Scope scope = open(tenantId, null)) {
            return work.get();
        }
    }

    public static void runAs(UUID tenantId, Runnable work) {
        try (Scope scope = open(tenantId, null)) {
            work.run();
        }
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
