package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderEmail.EmailPreview;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Sends the purchase order by SMTP, which is the only channel PaceSetter offers.
 *
 * <p>The sender is optional on purpose: with no {@code spring.mail.host} Spring creates no
 * {@link JavaMailSender}, and an app that is not set up to send must still start, list orders and
 * render previews. Asking it to send then fails with what to configure, rather than at boot.
 */
@Component
public class SupplierOrderMailer {

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderMailer.class);

    private final ObjectProvider<JavaMailSender> senders;

    public SupplierOrderMailer(ObjectProvider<JavaMailSender> senders) {
        this.senders = senders;
    }

    /** @return whether an SMTP server is configured at all */
    public boolean canSend() {
        return senders.getIfAvailable() != null;
    }

    /** Sends the rendered message. Throws when SMTP is not configured or the server refuses it. */
    public void send(EmailPreview mail) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No SMTP server configured: set spring.mail.host "
                    + "(and the mailbox credentials) before sending orders to PaceSetter.");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(mail.from());
            helper.setTo(addresses(mail.to()));
            if (mail.cc() != null && !mail.cc().isBlank()) {
                helper.setCc(addresses(mail.cc()));
            }
            if (mail.bcc() != null && !mail.bcc().isBlank()) {
                helper.setBcc(addresses(mail.bcc()));
            }
            if (mail.replyTo() != null && !mail.replyTo().isBlank()) {
                helper.setReplyTo(mail.replyTo());
            }
            helper.setSubject(mail.subject());
            helper.setText(mail.body(), true);
            sender.send(message);
            log.info("Sent purchase order email to {} (subject: {})", mail.to(), mail.subject());
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the PaceSetter order email: " + e.getMessage(), e);
        }
    }

    /** {@code to} and {@code cc} are configured as one comma-separated string, as a mail client writes them. */
    private static String[] addresses(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
