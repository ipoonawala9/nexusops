package com.nexusops.notifications.internal;

import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);
    private final MailSender mailSender;

    MailDispatcher(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Never fails the (already committed) business operation; the recipient address is not logged (PII). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(MailRequested event) {
        try {
            mailSender.send(event.mail());
        } catch (RuntimeException e) {
            log.warn("Mail delivery failed (subject: {})", event.mail().subject(), e);
        }
    }
}
