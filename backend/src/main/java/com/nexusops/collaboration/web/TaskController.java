package com.nexusops.collaboration.web;

import com.nexusops.collaboration.AssigneeView;
import com.nexusops.collaboration.TaskQuery;
import com.nexusops.collaboration.TaskService;
import com.nexusops.collaboration.TaskView;
import com.nexusops.collaboration.web.CollaborationDtos.TaskRequest;
import com.nexusops.collaboration.web.CollaborationDtos.TaskStatusRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
class TaskController {

    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('collaboration.task.read')")
    PageResponse<TaskView> list(@RequestParam(required = false) String assignee,
            @RequestParam(required = false) String status, @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return tasks.list(new TaskQuery(assignee, status, subjectType, subjectId, q), page, size);
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAuthority('collaboration.task.manage')")
    List<AssigneeView> assignees(@RequestParam(required = false) String q) {
        return tasks.assignees(q);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('collaboration.task.read')")
    TaskView get(@PathVariable UUID id) {
        return tasks.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('collaboration.task.manage')")
    TaskView create(@RequestBody TaskRequest request) {
        return tasks.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('collaboration.task.manage')")
    TaskView update(@PathVariable UUID id, @RequestBody TaskRequest request) {
        return tasks.update(id, request.command(), request.version());
    }

    /** Managers, or the assignee with read access (checked in TaskService, 403). */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('collaboration.task.read', 'collaboration.task.manage')")
    TaskView changeStatus(@PathVariable UUID id, @RequestBody TaskStatusRequest request) {
        return tasks.changeStatus(id, request.status());
    }
}
