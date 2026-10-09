package com.nexusops.collaboration;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace search (D12): contains-match over every subject type the caller may read, 5 hits per type. */
@Service
public class SearchService {

    static final List<String> ORDER = List.of("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT", "PURCHASE_ORDER",
            "SALES_ORDER", "TICKET", "KB_ARTICLE");
    static final int PER_TYPE = 5;

    private final List<SubjectResolver> resolvers;

    SearchService(List<SubjectResolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(String q) {
        TenantContext.requireTenantId();
        String text = q == null ? "" : q.strip();
        if (text.length() < 2 || text.length() > 100) {
            throw ApiProblem.badRequestField("q", "Enter 2 to 100 characters.");
        }
        String pattern = Text.containsPattern(text);
        return resolvers.stream().filter(r -> CurrentAuthorities.has(r.readPermission()))
                .sorted(Comparator.comparingInt(r -> rank(r.type())))
                .flatMap(r -> r.search(pattern, PER_TYPE).stream()).toList();
    }

    private static int rank(String type) {
        int index = ORDER.indexOf(type);
        return index < 0 ? ORDER.size() : index;
    }
}
