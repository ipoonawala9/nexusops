package com.nexusops.crm;

/** Permission codes of the CRM module (V15). All have module_code CRM. */
public final class CrmPermissions {

    public static final String LEAD_READ = "crm.lead.read";
    public static final String LEAD_MANAGE = "crm.lead.manage";
    public static final String OPPORTUNITY_READ = "crm.opportunity.read";
    public static final String OPPORTUNITY_MANAGE = "crm.opportunity.manage";
    public static final String PIPELINE_MANAGE = "crm.pipeline.manage";
    public static final String CUSTOMER_READ = "crm.customer.read";

    private CrmPermissions() {}
}
