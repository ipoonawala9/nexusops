package com.nexusops.helpdesk;

/** The ticket workflow (D6). CLOSED is final; RESOLVED → OPEN is a reopen. */
public enum TicketStatus {
    NEW, OPEN, PENDING, RESOLVED, CLOSED;

    public boolean isOpen() {
        return this == NEW || this == OPEN || this == PENDING;
    }

    public boolean canMoveTo(TicketStatus target) {
        return switch (this) {
            case NEW -> target == OPEN || target == PENDING || target == RESOLVED;
            case OPEN -> target == PENDING || target == RESOLVED;
            case PENDING -> target == OPEN || target == RESOLVED;
            case RESOLVED -> target == OPEN || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
