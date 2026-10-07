package com.nexusops.crm.domain;

import com.nexusops.crm.LeadSource;
import java.math.BigDecimal;
import java.util.UUID;

/** Validated lead fields (LeadService builds these). */
public record LeadDetails(String firstName, String lastName, String companyName, String jobTitle, String email,
        String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency, String description) {}
