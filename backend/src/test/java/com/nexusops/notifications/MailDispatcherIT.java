package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class MailDispatcherIT extends IntegrationTestSupport {

    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired RecordingMailSender mail;

    @Test
    void mailIsSentOnlyAfterCommit() {
        tx.executeWithoutResult(s -> events.publishEvent(new MailRequested(new OutgoingMail("commit@x.test", "Hi", "body"))));
        assertThat(mail.sentTo("commit@x.test")).hasSize(1);
    }

    @Test
    void mailIsNotSentWhenTransactionRollsBack() {
        tx.executeWithoutResult(s -> {
            events.publishEvent(new MailRequested(new OutgoingMail("rollback@x.test", "Hi", "body")));
            s.setRollbackOnly();
        });
        assertThat(mail.sentTo("rollback@x.test")).isEmpty();
    }

    @Test
    void mailOutsideTransactionIsSentImmediately() {
        events.publishEvent(new MailRequested(new OutgoingMail("now@x.test", "Hi", "body")));
        assertThat(mail.sentTo("now@x.test")).hasSize(1);
    }
}
