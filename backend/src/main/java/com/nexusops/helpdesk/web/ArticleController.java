package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.ArticleService;
import com.nexusops.helpdesk.ArticleStatus;
import com.nexusops.helpdesk.ArticleSummary;
import com.nexusops.helpdesk.ArticleView;
import com.nexusops.helpdesk.web.HelpDeskDtos.ArticleRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
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
@RequestMapping("/api/v1/helpdesk/articles")
class ArticleController {

    private final ArticleService articles;

    ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.article.read')")
    PageResponse<ArticleSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) ArticleStatus status, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return articles.list(q, status, categoryId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.article.read')")
    ArticleView get(@PathVariable UUID id) {
        return articles.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView create(@RequestBody ArticleRequest request) {
        return articles.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView update(@PathVariable UUID id, @RequestBody ArticleRequest request) {
        return articles.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView publish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.publish(id, request.version());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView unpublish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.unpublish(id, request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView archive(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.archive(id, request.version());
    }
}
