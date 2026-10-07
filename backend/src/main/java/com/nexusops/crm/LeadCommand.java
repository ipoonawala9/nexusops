package com.nexusops.crm;

import java.math.BigDecimal;
import java.util.UUID;

/** Raw input; LeadService validates it. Source defaults to OTHER; ownerId null means the creator on create only. */
public record LeadCommand(String firstName, String lastName, String companyName, String jobTitle, String email,
        String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency, String description) {}
