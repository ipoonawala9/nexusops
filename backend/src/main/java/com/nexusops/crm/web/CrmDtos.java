package com.nexusops.crm.web;

import com.nexusops.crm.LeadCommand;
import com.nexusops.crm.LeadSource;
import com.nexusops.crm.LeadStatus;
import com.nexusops.crm.OpportunityCommand;
import com.nexusops.crm.StageCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class CrmDtos {

    private CrmDtos() {}

    record StageRequest(String name, Integer probability, Long version) {
        StageCommand command() {
            return new StageCommand(name, probability);
        }
    }

    record StageOrderRequest(List<UUID> stageIds) {}

    record LeadRequest(String firstName, String lastName, String companyName, String jobTitle, String email,
            String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency,
            String description, Long version) {
        LeadCommand command() {
            return new LeadCommand(firstName, lastName, companyName, jobTitle, email, phone, source, ownerId,
                    estimatedValue, currency, description);
        }
    }

    record LeadStatusRequest(LeadStatus status, String reason, Long version) {}

    record OpportunityRequest(String name, UUID accountId, UUID contactId, UUID stageId, BigDecimal amount,
            String currency, LocalDate expectedCloseOn, UUID ownerId, String description, Long version) {
        OpportunityCommand command() {
            return new OpportunityCommand(name, accountId, contactId, stageId, amount, currency, expectedCloseOn,
                    ownerId, description);
        }
    }

    record StageMoveRequest(UUID stageId, String lostReason, Long version) {}
}
