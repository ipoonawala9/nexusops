package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LeadView(UUID id, String name, String firstName, String lastName, String companyName, String jobTitle,
        String email, String phone, LeadSource source, LeadStatus status, MemberRef owner, BigDecimal estimatedValue,
        String currency, String description, String disqualifyReason, Instant disqualifiedAt, Instant convertedAt,
        PartyRef convertedPerson, PartyRef convertedOrganization, UUID convertedOpportunityId, MemberRef createdBy,
        Instant createdAt, Instant updatedAt, long version) {}
