package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;

/** D3: NEW, CONTACTED and QUALIFIED are open and interchangeable; DISQUALIFIED reopens only as NEW; CONVERTED is final. */
public enum LeadStatus {
    NEW, CONTACTED, QUALIFIED, DISQUALIFIED, CONVERTED;

    static final String CONVERTED_FINAL = "This lead was converted and can no longer be changed.";

    public boolean isOpen() {
        return this == NEW || this == CONTACTED || this == QUALIFIED;
    }

    /** Throws when {@code from → to} isn't allowed through a status change (conversion has its own route). */
    static void check(LeadStatus from, LeadStatus to) {
        if (to == CONVERTED) {
            throw ApiProblem.badRequestField("status", "Convert the lead instead.");
        }
        if (from == CONVERTED) {
            throw ApiProblem.conflict(CONVERTED_FINAL);
        }
        if (from == DISQUALIFIED && to != NEW && to != DISQUALIFIED) {
            throw ApiProblem.conflict("Reopen the lead as New first.");
        }
    }
}
