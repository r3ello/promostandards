package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderEmail.EmailPreview;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Sends the purchase order by SMTP, which is the only channel PaceSetter offers.
 *
 * <p>Being unconfigured is not a startup failure: an app nobody has given a mailbox must still start,
 * list orders and render previews. {@link #problem()} is where that is noticed, and it is asked
 * before a message is built — the mail server's own answer to a half-configured mailbox
 * ({@code AuthenticationFailedException: failed to connect, no password specified?}) names neither
 * the setting nor this app.
 */
@Component
public class SupplierOrderMailer {

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderMailer.class);

    private final ObjectProvider<JavaMailSender> senders;

    public SupplierOrderMailer(ObjectProvider<JavaMailSender> senders) {
        this.senders = senders;
    }

    /**
     * @return why a send cannot work, before one is attempted, or null when it can be tried. Said
     * here rather than by the mail server: its own answer to a half-configured mailbox is
     * {@code AuthenticationFailedException: failed to connect, no password specified?}, which names
     * neither the setting nor the app.
     */
    public String problem() {
        JavaMailSender sender = senders.getIfAvailable();
        // A blank host still gets a sender bean: application.yaml always defines spring.mail.host
        // (empty by default) and Spring's condition is that the property EXISTS, not that it says
        // anything. So "not configured" has to be recognised here, not inferred from a missing bean.
        if (sender == null || (sender instanceof JavaMailSenderImpl impl0 && isBlank(impl0.getHost()))) {
            return "No SMTP server configured: set spring.mail.host (MAIL_HOST) and the mailbox "
                    + "credentials before sending orders to PaceSetter.";
        }
        if (sender instanceof JavaMailSenderImpl impl
                && Boolean.parseBoolean(impl.getJavaMailProperties().getProperty("mail.smtp.auth"))
                && (isBlank(impl.getUsername()) || isBlank(impl.getPassword()))) {
            return "The SMTP server at " + impl.getHost() + " is set to authenticate, but the mailbox "
                    + "credentials are empty: set MAIL_USERNAME and MAIL_PASSWORD (an app password for "
                    + "Google Workspace or Microsoft 365), or MAIL_SMTP_AUTH=false for a server that "
                    + "takes none.";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Sends the rendered message. Throws when SMTP is not configured or the server refuses it. */
    public void send(EmailPreview mail) {
        String problem = problem();
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        JavaMailSender sender = senders.getIfAvailable();
        try {
            MimeMessage message = sender.createMimeMessage();
            // Multipart: the same PO as text and as HTML. A mail client picks the HTML; everything that
            // reads mail without rendering it — spam scoring, a phone's preview, an archive — reads the
            // text, and a message with no text part at all is scored worse for it.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
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
            helper.setText(mail.text(), mail.body());
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
