package com.nexusops.collaboration;

import java.util.UUID;

/** A search result: {@code detail} is a short hint (email, SKU, company…), may be null. */
public record SearchHit(String type, UUID id, String label, String detail, boolean archived) {}
