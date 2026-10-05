package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexusops.notifications.internal.SmtpMailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSenderTest {

    @Test
    void sendsPlainTextFromConfiguredAddress() {
        JavaMailSender javaMail = mock(JavaMailSender.class);
        new SmtpMailSender(javaMail, "NexusOps <no-reply@nexusops.local>")
                .send(new OutgoingMail("to@x.test", "Subject", "Body"));
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMail).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("to@x.test");
        assertThat(captor.getValue().getFrom()).isEqualTo("NexusOps <no-reply@nexusops.local>");
        assertThat(captor.getValue().getSubject()).isEqualTo("Subject");
        assertThat(captor.getValue().getText()).isEqualTo("Body");
    }
}
