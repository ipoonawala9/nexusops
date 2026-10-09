package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusops.notifications.internal.SmtpMailSender;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSenderTest {

    private static MimeMessage sent(OutgoingMail mail) {
        JavaMailSender javaMail = mock(JavaMailSender.class);
        when(javaMail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new SmtpMailSender(javaMail, "NexusOps <no-reply@nexusops.local>").send(mail);
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMail).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendsPlainTextFromConfiguredAddress() throws Exception {
        MimeMessage message = sent(new OutgoingMail("to@x.test", "Subject", "Body"));
        assertThat(message.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                .containsExactly("to@x.test");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@nexusops.local");
        assertThat(from.getPersonal()).isEqualTo("NexusOps");
        assertThat(message.getHeader("Reply-To")).isNull();
        assertThat(message.getSubject()).isEqualTo("Subject");
        assertThat(message.getContent()).isEqualTo("Body");
    }

    @Test
    void aSenderNameReplacesTheConfiguredOneAndReplyToIsSet() throws Exception {
        MimeMessage message = sent(new OutgoingMail("to@x.test", "Subject", "Body", "Sahyadri, \"Stores\" Ltd",
                "agent@sahyadri.test"));
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@nexusops.local");
        assertThat(from.getPersonal()).isEqualTo("Sahyadri, \"Stores\" Ltd");
        assertThat(message.getReplyTo()).extracting(a -> ((InternetAddress) a).getAddress())
                .containsExactly("agent@sahyadri.test");
    }
}
