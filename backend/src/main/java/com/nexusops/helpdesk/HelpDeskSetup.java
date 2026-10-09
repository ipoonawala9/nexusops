package com.nexusops.helpdesk;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with the default categories and SLA policies, in the signup transaction (D5, D8). */
@Component
class HelpDeskSetup {

    private final CategoryService categories;
    private final SlaPolicyService policies;

    HelpDeskSetup(CategoryService categories, SlaPolicyService policies) {
        this.categories = categories;
        this.policies = policies;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        categories.seedDefaults();
        policies.seedDefaults();
    }
}
