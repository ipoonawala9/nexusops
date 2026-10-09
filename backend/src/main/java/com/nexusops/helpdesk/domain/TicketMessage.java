package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.MessageKind;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ticket_messages")
public class TicketMessage extends TenantOwnedEntity {

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageKind kind;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(name = "emailed_to", updatable = false)
    private String emailedTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketMessage() {}

    public TicketMessage(UUID id, UUID ticketId, MessageKind kind, String body, UUID authorId, String emailedTo,
            Instant createdAt) {
        super(id);
        this.ticketId = ticketId;
        this.kind = kind;
        this.body = body;
        this.authorId = authorId;
        this.emailedTo = emailedTo;
        this.createdAt = createdAt;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public MessageKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getEmailedTo() {
        return emailedTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
