package com.nexusops.notifications;

/** Publish inside a transaction; the mail is delivered only after commit (MailDispatcher). */
public record MailRequested(OutgoingMail mail) {}
