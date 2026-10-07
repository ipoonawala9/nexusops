package com.nexusops.catalog;

public record ProductQuery(String q, ProductKind kind, boolean archived) {}
