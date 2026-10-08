package com.nexusops.crm.web;

import com.nexusops.crm.PipelineService;
import com.nexusops.crm.StageView;
import com.nexusops.crm.web.CrmDtos.StageOrderRequest;
import com.nexusops.crm.web.CrmDtos.StageRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm/pipeline/stages")
class StageController {

    private final PipelineService pipeline;

    StageController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    List<StageView> list() {
        return pipeline.stages();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    StageView create(@RequestBody StageRequest request) {
        return pipeline.create(request.command());
    }

    @PutMapping("/order")
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    List<StageView> reorder(@RequestBody StageOrderRequest request) {
        return pipeline.reorder(request.stageIds());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    StageView update(@PathVariable UUID id, @RequestBody StageRequest request) {
        return pipeline.update(id, request.command(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    void delete(@PathVariable UUID id) {
        pipeline.delete(id);
    }
}
