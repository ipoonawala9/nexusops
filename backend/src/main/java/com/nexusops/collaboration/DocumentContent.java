package com.nexusops.collaboration;

public record DocumentContent(String fileName, String contentType, byte[] bytes) {}
