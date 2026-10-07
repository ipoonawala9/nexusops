package com.nexusops.catalog;

import com.nexusops.catalog.domain.Product;
import com.nexusops.catalog.domain.ProductRepository;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Products as collaboration subjects (type PRODUCT). */
@Component
class ProductSubjects implements SubjectResolver {

    static final String TYPE = "PRODUCT";

    private final ProductRepository products;

    ProductSubjects(ProductRepository products) {
        this.products = products;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return CatalogPermissions.PRODUCT_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return products.findById(id).map(ProductSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return products.findAllById(ids).stream().collect(Collectors.toMap(Product::getId, ProductSubjects::ref));
    }

    private static SubjectRef ref(Product product) {
        return new SubjectRef(TYPE, product.getId(), product.getName() + " (" + product.getSku() + ")",
                product.isArchived());
    }
}
