package com.nexusops.notifications;

/** Delivery port. SMTP (Mailpit locally) today; SES later. */
public interface MailSender {
    void send(OutgoingMail mail);
}
