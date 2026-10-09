package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.ArticleStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A knowledge base article. The generated {@code search} column is not mapped. */
@Entity
@Table(name = "kb_articles")
public class KbArticle extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArticleStatus status;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected KbArticle() {}

    public KbArticle(UUID id, String title, String body, UUID categoryId, UUID authorId) {
        super(id);
        this.status = ArticleStatus.DRAFT;
        this.authorId = authorId;
        this.createdAt = Instant.now();
        apply(title, body, categoryId);
    }

    public void apply(String newTitle, String newBody, UUID newCategory) {
        this.title = newTitle;
        this.body = newBody;
        this.categoryId = newCategory;
        this.updatedAt = Instant.now();
    }

    public void publish(Instant now) {
        this.status = ArticleStatus.PUBLISHED;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void unpublish(Instant now) {
        this.status = ArticleStatus.DRAFT;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.status = ArticleStatus.ARCHIVED;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public ArticleStatus getStatus() {
        return status;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
