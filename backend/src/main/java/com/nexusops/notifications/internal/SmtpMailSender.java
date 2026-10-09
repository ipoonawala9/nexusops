package com.nexusops.notifications.internal;

import com.nexusops.notifications.MailSender;
import com.nexusops.notifications.OutgoingMail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMail;
    private final InternetAddress from;

    public SmtpMailSender(JavaMailSender javaMail, @Value("${nexusops.mail.from}") String from) {
        this.javaMail = javaMail;
        try {
            this.from = new InternetAddress(from, true);
        } catch (AddressException e) {
            throw new IllegalArgumentException("nexusops.mail.from is not an email address: " + from, e);
        }
    }

    @Override
    public void send(OutgoingMail mail) {
        MimeMessage message = javaMail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            // a sender name changes only the display name: the address stays the platform's (SPF/DKIM)
            helper.setFrom(mail.fromName() == null ? from
                    : new InternetAddress(from.getAddress(), mail.fromName(), StandardCharsets.UTF_8.name()));
            if (mail.replyTo() != null) {
                helper.setReplyTo(new InternetAddress(mail.replyTo(), true));
            }
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.textBody(), false);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Could not prepare mail to " + mail.to(), e);
        }
        javaMail.send(message);
    }
}
