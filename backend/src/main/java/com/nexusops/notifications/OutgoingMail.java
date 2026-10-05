package com.nexusops.notifications;

public record OutgoingMail(String to, String subject, String textBody) {}
