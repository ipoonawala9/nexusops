package com.nexusops.identity.security;

import com.nexusops.authorization.RolesChanged;
import com.nexusops.tenancy.ModulesChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Permission-affecting changes in other modules take effect on the members' very next request. */
@Component
class PrincipalCacheInvalidator {

    private final PrincipalStateCache cache;

    PrincipalCacheInvalidator(PrincipalStateCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(RolesChanged event) {
        cache.evictTenant(event.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ModulesChanged event) {
        cache.evictTenant(event.tenantId());
    }
}
