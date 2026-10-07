package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A board card / dashboard row. */
public record OpportunitySummary(UUID id, String name, PartyRef account, StageRef stage, BigDecimal amount,
        String currency, LocalDate expectedCloseOn, MemberRef owner, long version) {}
