package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.CategoryService;
import com.nexusops.helpdesk.CategoryView;
import com.nexusops.helpdesk.web.HelpDeskDtos.CategoryRequest;
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
@RequestMapping("/api/v1/helpdesk/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<CategoryView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return categories.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView create(@RequestBody CategoryRequest request) {
        return categories.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView update(@PathVariable UUID id, @RequestBody CategoryRequest request) {
        return categories.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView archive(@PathVariable UUID id) {
        return categories.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView restore(@PathVariable UUID id) {
        return categories.restore(id);
    }
}
