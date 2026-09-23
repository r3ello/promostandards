package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The email that carries an order to PaceSetter: who it goes to, and the template it is built from
 * ({@code orders.pacesetter.*}). PaceSetter takes no orders by API — the channel is email — so the
 * whole PO lives in this one message.
 *
 * <p>The body is a file, not code: {@code template} is a Spring resource location
 * ({@code classpath:email/pacesetter-po.html}, or {@code file:./config/…} to edit it on the server
 * without a rebuild), read on every render so an edit shows up on the next preview. Its placeholders
 * are {@code {{name}}} — see {@link SupplierOrderEmail}.
 *
 * @param enabled       whether the app may send. False (the default) still renders the preview: the
 *                      email can be read and copied before anything can leave the server.
 * @param to            PaceSetter's order mailbox; without it nothing can be sent
 * @param cc            comma-separated copies — the client's own mailbox belongs here, so the shop
 *                      keeps the thread PaceSetter will reply into
 * @param bcc           comma-separated blind copies, when a mailbox should get it without PaceSetter
 *                      seeing the address
 * @param from          the sender; PaceSetter must recognise it as the distributor's own mailbox
 * @param replyTo       where PaceSetter's answers go, when that is not {@code from}
 * @param subject       subject line, same placeholders as the body
 * @param template      resource location of the body template
 * @param accountNumber the distributor account PaceSetter files the order under
 * @param signature     who the message signs off as
 * @param contact       the person at PaceSetter it is addressed to ("Hi Dana,"); blank greets the
 *                      team rather than nobody
 * @param shipAccount   the shop's own carrier account PaceSetter ships on ({@code UPS #4E4W93}).
 *                      Blank leaves the sentence about it out: an invented account number would have
 *                      the shipment billed to a stranger.
 */
@ConfigurationProperties(prefix = "orders.pacesetter")
public record SupplierOrderProperties(Boolean enabled, String to, String cc, String bcc, String from,
                                      String replyTo, String subject, String template,
                                      String accountNumber, String signature, String contact,
                                      String shipAccount) {

    private static final String DEFAULT_TEMPLATE = "classpath:email/pacesetter-po.html";
    /** The subject line the shop has always used, so PaceSetter's inbox rules keep working. */
    private static final String DEFAULT_SUBJECT = "TrophyPartner.com Order P.O. # {{poNumber}}";

    /** Not {@code enabled()}: a record accessor cannot narrow {@code Boolean} to {@code boolean}. */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public String template() {
        return template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
    }

    public String subject() {
        return subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject;
    }
}
