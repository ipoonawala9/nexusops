package com.nexusops.notifications.internal;

import com.nexusops.notifications.MailSender;
import com.nexusops.notifications.OutgoingMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMail;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMail, @Value("${nexusops.mail.from}") String from) {
        this.javaMail = javaMail;
        this.from = from;
    }

    @Override
    public void send(OutgoingMail mail) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.textBody());
        javaMail.send(message);
    }
}
