package com.nexusops.crm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Validated opportunity fields except the stage (OpportunityService builds these). */
public record OpportunityDetails(String name, UUID accountId, UUID contactId, BigDecimal amount, String currency,
        LocalDate expectedCloseOn, UUID ownerId, String description) {}
