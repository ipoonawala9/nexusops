package com.nexusops.notifications;

/**
 * One plain-text email. {@code fromName} (optional) replaces the display name of the configured sender address, so a
 * workspace can write as itself; {@code replyTo} (optional) is where the recipient's answer goes.
 */
public record OutgoingMail(String to, String subject, String textBody, String fromName, String replyTo) {

    public OutgoingMail(String to, String subject, String textBody) {
        this(to, subject, textBody, null, null);
    }
}
