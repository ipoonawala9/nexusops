package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.helpdesk.domain.KbArticle;
import com.nexusops.helpdesk.domain.KbArticleRepository;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Knowledge base (D13). Without helpdesk.article.manage only published articles exist. */
@Service
public class ArticleService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";

    private final KbArticleRepository articles;
    private final HelpDeskSearch search;
    private final CategoryService categories;
    private final Members members;
    private final AuditService audit;

    ArticleService(KbArticleRepository articles, HelpDeskSearch search, CategoryService categories, Members members,
            AuditService audit) {
        this.articles = articles;
        this.search = search;
        this.categories = categories;
        this.members = members;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleSummary> list(String q, ArticleStatus status, UUID categoryId, Integer page,
            Integer size) {
        TenantContext.requireTenantId();
        List<ArticleStatus> statuses;
        if (!canManage()) {
            statuses = status == null || status == ArticleStatus.PUBLISHED ? List.of(ArticleStatus.PUBLISHED) : List.of();
        } else {
            statuses = status == null ? List.of(ArticleStatus.DRAFT, ArticleStatus.PUBLISHED) : List.of(status);
        }
        Pageable paging = Paging.of(page, size);
        if (statuses.isEmpty()) {
            return new PageResponse<>(List.of(), paging.getPageNumber(), paging.getPageSize(), 0);
        }
        HelpDeskSearch.ArticlePage found = search.articlePage(q, statuses, categoryId, paging.getPageSize(),
                paging.getOffset());
        return new PageResponse<>(summaries(found.ids()), paging.getPageNumber(), paging.getPageSize(), found.total());
    }

    @Transactional(readOnly = true)
    public ArticleView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    public ArticleView create(ArticleCommand command) {
        TenantContext.requireTenantId();
        Fields f = validate(command, null);
        KbArticle article = new KbArticle(Ids.newId(), f.title(), f.body(), f.categoryId(),
                TenantContext.userId().orElse(null));
        articles.saveAndFlush(article);
        audit.record(AuditEntry.of("ArticleCreated", "KbArticle", article.getId()).withAfter(snapshot(article)));
        return view(article);
    }

    @Transactional
    public ArticleView update(UUID id, ArticleCommand command, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        Fields f = validate(command, article);
        Map<String, Object> before = snapshot(article);
        article.apply(f.title(), f.body(), f.categoryId());
        articles.flush();
        audit.record(AuditEntry.of("ArticleUpdated", "KbArticle", id).withBefore(before).withAfter(snapshot(article)));
        return view(article);
    }

    @Transactional
    public ArticleView publish(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            article.publish(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticlePublished", "KbArticle", id).withAfter(Map.of("title", article.getTitle())));
        }
        return view(article);
    }

    @Transactional
    public ArticleView unpublish(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            article.unpublish(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticleUnpublished", "KbArticle", id).withAfter(Map.of("title", article.getTitle())));
        }
        return view(article);
    }

    @Transactional
    public ArticleView archive(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        if (article.getStatus() != ArticleStatus.ARCHIVED) {
            article.archive(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticleArchived", "KbArticle", id).withBefore(snapshot(article)));
        }
        return view(article);
    }

    /** Summaries in the order of {@code ids} (search ranking). */
    List<ArticleSummary> summaries(List<UUID> ids) {
        Map<UUID, KbArticle> byId = articles.findAllById(ids).stream()
                .collect(Collectors.toMap(KbArticle::getId, Function.identity()));
        List<KbArticle> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<UUID, TicketCategory> cats = categories.byIds(ordered.stream().map(KbArticle::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return ordered.stream().map(a -> new ArticleSummary(a.getId(), a.getTitle(), excerpt(a.getBody()),
                CategoryService.ref(cats.get(a.getCategoryId())), a.getStatus(), a.getPublishedAt(), a.getUpdatedAt()))
                .toList();
    }

    private record Fields(String title, String body, UUID categoryId) {}

    private Fields validate(ArticleCommand c, KbArticle current) {
        String title = Text.required(c.title(), 200, "title").replaceAll("\\s+", " ");
        String body = Text.required(c.body(), 50_000, "body");
        if (c.categoryId() != null) {
            TicketCategory category = categories.resolve(c.categoryId(), "categoryId");
            if (current == null || !c.categoryId().equals(current.getCategoryId())) {
                CategoryService.requireNotArchived(category);
            }
        }
        return new Fields(title, body, c.categoryId());
    }

    /** Drafts and archived articles don't exist for readers without manage (404, not 403: no leak of their titles). */
    private KbArticle find(UUID id) {
        TenantContext.requireTenantId();
        KbArticle article = articles.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
        if (article.getStatus() != ArticleStatus.PUBLISHED && !canManage()) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        return article;
    }

    private static boolean canManage() {
        return CurrentAuthorities.has(HelpDeskPermissions.ARTICLE_MANAGE);
    }

    private static void checkVersion(KbArticle article, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (article.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static void requireNotArchived(KbArticle article) {
        if (article.getStatus() == ArticleStatus.ARCHIVED) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    private ArticleView view(KbArticle a) {
        TicketCategory category = a.getCategoryId() == null ? null
                : categories.byIds(List.of(a.getCategoryId())).get(a.getCategoryId());
        Members.Member author = a.getAuthorId() == null ? null : members.findAll(List.of(a.getAuthorId())).get(a.getAuthorId());
        return new ArticleView(a.getId(), a.getTitle(), a.getBody(), CategoryService.ref(category), a.getStatus(),
                author == null ? null : new MemberRef(author.id(), author.name()), a.getPublishedAt(), a.getCreatedAt(),
                a.getUpdatedAt(), a.getVersion());
    }

    private static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private static Map<String, Object> snapshot(KbArticle a) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", a.getTitle());
        values.put("categoryId", a.getCategoryId() == null ? null : a.getCategoryId().toString());
        values.put("status", a.getStatus().name());
        return values;
    }
}
