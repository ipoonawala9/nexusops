package com.nexusops.crm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Raw input. stageId: create only (null = first open stage); PUT keeps the stage (moves use the /stage route).
 * ownerId null means the creator on create, unassigned on update. */
public record OpportunityCommand(String name, UUID accountId, UUID contactId, UUID stageId, BigDecimal amount,
        String currency, LocalDate expectedCloseOn, UUID ownerId, String description) {}
