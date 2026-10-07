package com.nexusops.collaboration.web;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SearchService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    /** Authorization is per record type (each type's read permission), applied by SearchService. */
    @GetMapping("/api/v1/search")
    @PreAuthorize("isAuthenticated()")
    List<SearchHit> search(@RequestParam(required = false) String q) {
        return search.search(q);
    }
}
