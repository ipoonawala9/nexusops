package com.nexusops.audit.web;

import com.nexusops.audit.AuditEventView;
import com.nexusops.audit.AuditQuery;
import com.nexusops.audit.AuditQueryService;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/v1/audit-events")
    @PreAuthorize("hasAuthority('audit.event.read')")
    PageResponse<AuditEventView> search(@RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return audit.search(new AuditQuery(action, entityType, actorId, from, to, page, size));
    }
}
