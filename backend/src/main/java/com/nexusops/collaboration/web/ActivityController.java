package com.nexusops.collaboration.web;

import com.nexusops.collaboration.ActivityService;
import com.nexusops.collaboration.ActivityView;
import com.nexusops.collaboration.web.CollaborationDtos.ActivityRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activities")
class ActivityController {

    private final ActivityService activities;

    ActivityController(ActivityService activities) {
        this.activities = activities;
    }

    /** Authorization is the subject's read permission, checked by Subjects (403). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    PageResponse<ActivityView> list(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId, @RequestParam(defaultValue = "false") boolean includeRelated,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return activities.list(subjectType, subjectId, includeRelated, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('collaboration.activity.create')")
    ActivityView log(@RequestBody ActivityRequest request) {
        return activities.log(request.command());
    }
}
