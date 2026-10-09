package com.nexusops.helpdesk;

import java.util.List;

/** What the agent should see next to a ticket (D12). */
public record TicketContext(List<TicketSummary> previousTickets, List<TicketSummary> possibleDuplicates,
        List<ArticleSummary> suggestedArticles) {}
