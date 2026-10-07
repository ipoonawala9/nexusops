package com.nexusops.crm;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Gives every new workspace the default pipeline, in the signup transaction (D13). */
@Component
class PipelineSetup {

    private final PipelineService pipeline;

    PipelineSetup(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        pipeline.seedDefaults();
    }
}
