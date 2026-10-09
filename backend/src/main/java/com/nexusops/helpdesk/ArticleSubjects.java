package com.nexusops.helpdesk;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.helpdesk.domain.KbArticle;
import com.nexusops.helpdesk.domain.KbArticleRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Articles as collaboration subjects (type KB_ARTICLE, D14). Search finds published articles only. */
@Component
class ArticleSubjects implements SubjectResolver {

    static final String TYPE = "KB_ARTICLE";

    private final KbArticleRepository articles;

    ArticleSubjects(KbArticleRepository articles) {
        this.articles = articles;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HelpDeskPermissions.ARTICLE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return articles.findById(id).map(ArticleSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return articles.findAllById(ids).stream().collect(Collectors.toMap(KbArticle::getId, ArticleSubjects::ref));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<KbArticle> spec = (root, cq, cb) -> cb.and(
                cb.equal(root.get("status"), ArticleStatus.PUBLISHED),
                cb.like(cb.lower(root.get("title")), pattern, '\\'));
        return articles.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"))))
                .map(a -> new SearchHit(TYPE, a.getId(), a.getTitle(), null, false)).getContent();
    }

    private static SubjectRef ref(KbArticle a) {
        return new SubjectRef(TYPE, a.getId(), a.getTitle(), a.getStatus() == ArticleStatus.ARCHIVED);
    }
}
