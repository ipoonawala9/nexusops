package com.nexusops.catalog.web;

import com.nexusops.catalog.ProductKind;
import com.nexusops.catalog.ProductQuery;
import com.nexusops.catalog.ProductService;
import com.nexusops.catalog.ProductView;
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
@RequestMapping("/api/v1/products")
class ProductController {

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog.product.read')")
    PageResponse<ProductView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) ProductKind kind, @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return products.list(new ProductQuery(q, kind, archived), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog.product.read')")
    ProductView get(@PathVariable UUID id) {
        return products.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.product.manage')")
    ProductView create(@RequestBody ProductRequest request) {
        return products.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog.product.manage')")
    ProductView update(@PathVariable UUID id, @RequestBody ProductRequest request) {
        return products.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.product.manage')")
    ProductView archive(@PathVariable UUID id) {
        return products.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.product.manage')")
    ProductView restore(@PathVariable UUID id) {
        return products.restore(id);
    }
}
