package com.nexusops.crm;

/** Raw input; PipelineService validates it. */
public record StageCommand(String name, Integer probability) {}
