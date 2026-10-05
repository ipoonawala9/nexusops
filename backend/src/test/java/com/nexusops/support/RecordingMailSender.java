package com.nexusops.support;

import com.nexusops.notifications.MailSender;
import com.nexusops.notifications.OutgoingMail;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing mail in tests instead of sending it. */
public class RecordingMailSender implements MailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9._%-]+)");
    private final List<OutgoingMail> sent = new ArrayList<>();

    @Override
    public synchronized void send(OutgoingMail mail) {
        sent.add(mail);
    }

    public synchronized List<OutgoingMail> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    public synchronized String lastTokenFor(String email) {
        List<OutgoingMail> mails = sentTo(email);
        if (mails.isEmpty()) {
            throw new AssertionError("No mail sent to " + email);
        }
        Matcher matcher = TOKEN.matcher(mails.getLast().textBody());
        if (!matcher.find()) {
            throw new AssertionError("No token link in mail to " + email);
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }
}
