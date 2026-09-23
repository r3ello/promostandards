package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.sync.SupplierOrderEmail;
import com.trophy.promostandards.sync.SupplierOrderEmail.EmailPreview;
import com.trophy.promostandards.sync.SupplierOrderEmail.Recipients;
import com.trophy.promostandards.sync.SupplierOrderMailer;
import com.trophy.promostandards.sync.SupplierOrderProperties;
import com.trophy.promostandards.sync.SupplierOrderService;
import com.trophy.promostandards.sync.SupplierOrderService.PendingOrder;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;
import com.trophy.promostandards.sync.SupplierOrderService.SendResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders on their way to PaceSetter (PLAN-PEDIDOS-PACESETTER.md §3). Read-only for now: the list of
 * orders still to send and what one of them would send. Confirming — marking the order and producing
 * the PO — waits for PaceSetter's order template.
 */
@RestController
@RequestMapping("/api/orders")
public class SupplierOrderController {

    private final SupplierOrderService orders;
    private final SupplierOrderEmail email;
    private final SupplierOrderMailer mailer;
    private final SupplierOrderProperties props;

    public SupplierOrderController(SupplierOrderService orders, SupplierOrderEmail email,
                                   SupplierOrderMailer mailer, SupplierOrderProperties props) {
        this.orders = orders;
        this.email = email;
        this.mailer = mailer;
        this.props = props;
    }

    /**
     * What the console needs before it offers to send: whether sending is switched on at all
     * ({@code orders.pacesetter.enabled}) and which mailbox is configured. Costs no Shopify call, so
     * the Orders view can ask it on load. {@code sendEnabled} false is what greys out the button —
     * the switch lives in configuration, not in the page.
     */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        String smtpProblem = mailer.problem();
        Map<String, Object> settings = new LinkedHashMap<>();
        // Both halves have to hold: the switch is on AND a mailbox that could actually accept the
        // message. Either way the console says which of the two is missing.
        settings.put("sendEnabled", props.isEnabled() && smtpProblem == null);
        settings.put("switchedOn", props.isEnabled());
        settings.put("smtpProblem", smtpProblem);
        settings.put("to", props.to());
        settings.put("cc", props.cc());
        settings.put("bcc", props.bcc());
        settings.put("from", props.from());
        return settings;
    }

    /** Open orders with PaceSetter lines left to fulfil that were never sent, newest first. */
    @GetMapping("/pacesetter-pending")
    public List<PendingOrder> pending() {
        return orders.pending();
    }

    /** What this order would send: its PaceSetter lines, the address, and what stops it. Writes nothing. */
    @GetMapping("/{orderId}/pacesetter-po")
    public Preview preview(@PathVariable String orderId) {
        return orders.preview(orderId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No Shopify order " + orderId));
    }

    /**
     * The email this order would send: recipients from {@code orders.pacesetter.*}, body from the
     * template file. Renders whatever the order's state, and says what would stop it going
     * ({@code missing}) — reading the message is how the template is checked before sending exists.
     */
    @GetMapping("/{orderId}/pacesetter-po/email")
    public EmailPreview emailPreview(@PathVariable String orderId,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String cc,
                                     @RequestParam(required = false) String bcc) {
        return email.render(preview(orderId), new Recipients(to, cc, bcc));
    }

    /**
     * Emails the PO to PaceSetter and marks the order as sent. Refuses with <b>409</b>, having sent
     * nothing, when the order is not ready or has been sent before — {@code resend=true} is how a
     * second send is asked for deliberately. The preview is read again here, so what goes out is the
     * order as it is now, not as the screen last saw it.
     */
    @PostMapping("/{orderId}/pacesetter-po")
    public SendResult send(@PathVariable String orderId,
                           @RequestParam(defaultValue = "false") boolean resend,
                           @RequestBody(required = false) Recipients recipients) {
        return orders.send(preview(orderId), resend,
                recipients == null ? Recipients.CONFIGURED : recipients);
    }
}
