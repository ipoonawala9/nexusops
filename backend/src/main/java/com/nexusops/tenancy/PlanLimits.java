package com.nexusops.tenancy;

/** Limits of the tenant's plan; {@code null} means unlimited. */
public record PlanLimits(Integer maxUsers, Integer maxModules) {}
