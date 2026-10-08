package com.nexusops.crm;

import java.util.UUID;

public record OpportunityQuery(String q, OpportunityStatus status, UUID stageId, UUID accountId, UUID contactId,
        String owner) {}
