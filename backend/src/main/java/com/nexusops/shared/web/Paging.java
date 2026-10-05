package com.nexusops.shared.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** `?page` (0-based, default 0) and `?size` (default 20, 1..100), per the plan's pagination constraint. */
public final class Paging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {}

    public static Pageable of(Integer page, Integer size) {
        return of(page, size, Sort.unsorted());
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiProblem.badRequestField("page", "Page must be 0 or greater.");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiProblem.badRequestField("size", "Size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(p, s, sort);
    }
}
