package com.nexusops.crm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** D4. Each choice links {@code existingId} or, without it, creates from its fields; a null choice means none. */
public record ConversionCommand(PersonChoice person, OrganizationChoice organization, NewOpportunity opportunity) {

    public record PersonChoice(UUID existingId, String firstName, String lastName, String jobTitle, String email,
            String phone, String duplicateReason) {}

    public record OrganizationChoice(UUID existingId, String name, String domain, String website, String email,
            String phone, String duplicateReason) {}

    /** Name defaults to the lead's name; amount and currency default to the lead's estimate. */
    public record NewOpportunity(String name, BigDecimal amount, String currency, UUID stageId,
            LocalDate expectedCloseOn) {}
}
