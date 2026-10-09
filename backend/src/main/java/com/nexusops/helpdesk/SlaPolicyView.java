package com.nexusops.helpdesk;

public record SlaPolicyView(Priority priority, int firstResponseMinutes, int resolutionMinutes, long version) {}
