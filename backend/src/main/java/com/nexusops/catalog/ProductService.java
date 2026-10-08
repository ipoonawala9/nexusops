package com.nexusops.catalog;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.domain.Product;
import com.nexusops.catalog.domain.ProductDetails;
import com.nexusops.catalog.domain.ProductRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Products and services. SKUs are unique per tenant ignoring case and are never reused. */
@Service
public class ProductService {

    static final String NOT_FOUND = "Record not found.";
    static final String ARCHIVED = "This record is archived.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String SKU_TAKEN = "Another product already uses this SKU.";
    private static final Pattern SKU = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*$");
    private static final int MAX_INTEGER_DIGITS = 15;

    private final ProductRepository products;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;
    private final List<ProductKindGuard> kindGuards;

    ProductService(ProductRepository products, TenantDirectory tenants, TenantLocks locks, AuditService audit,
            List<ProductKindGuard> kindGuards) {
        this.products = products;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
        this.kindGuards = kindGuards;
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) {
        return view(find(id));
    }

    /** Tenant-scoped lookup for other modules; unknown ids (or other tenants') are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, ProductBrief> briefs(Collection<UUID> ids) {
        TenantContext.requireTenantId();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(Product::getId,
                p -> new ProductBrief(p.getId(), p.getSku(), p.getName(), p.getKind(), p.getUnit(), p.getListPrice(),
                        p.getCurrency(), p.isArchived())));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductView> list(ProductQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        String q = Text.optional(query.q(), 100, "q");
        Specification<Product> spec = (root, cq, cb) -> query.archived()
                ? cb.isNotNull(root.get("archivedAt")) : cb.isNull(root.get("archivedAt"));
        if (query.kind() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("kind"), query.kind()));
        }
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("sku")), like, '\\'),
                    cb.like(cb.lower(root.get("name")), like, '\\')));
        }
        return PageResponse.from(products.findAll(spec, Paging.of(page, size, Sort.by("name", "id"))),
                ProductService::view);
    }

    @Transactional
    public ProductView create(ProductCommand command) {
        TenantContext.requireTenantId();
        ProductDetails details = validate(command);
        locks.lock("product-sku");
        if (products.existsBySkuIgnoreCase(details.sku())) {
            throw ApiProblem.conflictField("sku", SKU_TAKEN);
        }
        Product product = new Product(Ids.newId(), details);
        save(product);
        audit.record(AuditEntry.of("ProductCreated", "Product", product.getId()).withAfter(snapshot(product)));
        return view(product);
    }

    @Transactional
    public ProductView update(UUID id, ProductCommand command, Long version) {
        Product product = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (product.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        if (product.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        ProductDetails details = validate(command);
        if (!details.sku().equalsIgnoreCase(product.getSku())) {
            locks.lock("product-sku");
            if (products.existsBySkuIgnoreCaseAndIdNot(details.sku(), id)) {
                throw ApiProblem.conflictField("sku", SKU_TAKEN);
            }
        }
        if (details.kind() != product.getKind()) {
            ProductKind from = product.getKind();
            kindGuards.forEach(guard -> guard.beforeKindChange(id, from, details.kind()));
        }
        Map<String, Object> before = snapshot(product);
        product.apply(details);
        save(product);
        audit.record(AuditEntry.of("ProductUpdated", "Product", id).withBefore(before).withAfter(snapshot(product)));
        return view(product);
    }

    @Transactional
    public ProductView archive(UUID id) {
        Product product = find(id);
        if (!product.isArchived()) {
            product.archive(Instant.now());
            products.flush();
            audit.record(AuditEntry.of("ProductArchived", "Product", id).withBefore(snapshot(product)));
        }
        return view(product);
    }

    @Transactional
    public ProductView restore(UUID id) {
        Product product = find(id);
        if (product.isArchived()) {
            product.restore();
            products.flush();
            audit.record(AuditEntry.of("ProductRestored", "Product", id).withAfter(snapshot(product)));
        }
        return view(product);
    }

    private Product find(UUID id) {
        TenantContext.requireTenantId();
        return products.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private void save(Product product) {
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException race) {
            throw ApiProblem.conflictField("sku", SKU_TAKEN);
        }
    }

    private ProductDetails validate(ProductCommand command) {
        String sku = Text.required(command.sku(), 64, "sku");
        if (!SKU.matcher(sku).matches()) {
            throw ApiProblem.badRequestField("sku", "Use letters, digits and . _ / - only, starting with a letter or digit.");
        }
        String name = Text.required(command.name(), 200, "name");
        String description = Text.optional(command.description(), 2000, "description");
        ProductKind kind = command.kind() == null ? ProductKind.GOODS : command.kind();
        String unit = Text.optional(command.unit(), 20, "unit");
        BigDecimal price = command.listPrice();
        String currency = null;
        if (price != null) {
            if (price.signum() < 0) {
                throw ApiProblem.badRequestField("listPrice", "Enter a price of 0 or more.");
            }
            if (price.stripTrailingZeros().scale() > 4) {
                throw ApiProblem.badRequestField("listPrice", "Use at most 4 decimal places.");
            }
            if (price.precision() - price.scale() > MAX_INTEGER_DIGITS) {
                throw ApiProblem.badRequestField("listPrice", "Enter a smaller price.");
            }
            currency = currency(command.currency());
        }
        return new ProductDetails(sku, name, description, kind, unit == null ? "each" : unit, price, currency);
    }

    private String currency(String raw) {
        if (raw == null || raw.isBlank()) {
            return tenants.currentSettings().currency();
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3 && Currency.getInstance(code) != null) {
                return code;
            }
        } catch (IllegalArgumentException unknown) {
            // falls through to the field error
        }
        throw ApiProblem.badRequestField("currency", "Use a 3-letter currency code like USD.");
    }

    private static Map<String, Object> snapshot(Product product) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sku", product.getSku());
        values.put("name", product.getName());
        values.put("kind", product.getKind().name());
        values.put("unit", product.getUnit());
        if (product.getListPrice() != null) {
            values.put("listPrice", product.getListPrice().toPlainString());
            values.put("currency", product.getCurrency());
        }
        return values;
    }

    private static ProductView view(Product p) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getKind(), p.getUnit(),
                p.getListPrice(), p.getCurrency(), p.getArchivedAt(), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
    }
}
