package com.nexusops.tenancy;

/** Partial update: null fields are left unchanged. */
public record UpdateTenantSettings(String name, String timezone, String locale, String currency) {}
