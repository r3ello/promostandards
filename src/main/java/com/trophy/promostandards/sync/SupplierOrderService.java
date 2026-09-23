package com.trophy.promostandards.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Shopify order would send to PaceSetter, and which orders are still waiting to be sent.
 * Reads only: sending needs PaceSetter's order template (PLAN-PEDIDOS-PACESETTER.md §2), so this is
 * phase 1 without its confirm step.
 *
 * <p>A line is PaceSetter's when its variant carries {@code trophy_sync.vendor_sku} — the part id,
 * which is PaceSetter's own item number — or, failing that, when its product carries
 * {@code custom.ps_product_id}. The second kind is a product the sync has never touched: the line is
 * PaceSetter's but nothing says which part to order, so it <b>blocks</b> the order rather than being
 * left out of it (ordering the wrong item, or none, is worse than stopping).
 *
 * <p>An order counts as sent when it carries the {@code pacesetter-enviado} tag or the
 * {@code trophy_sync.pacesetter_po} metafield. The tag is what the pending search can exclude; the
 * metafield is the record of what went.
 */
@Service
public class SupplierOrderService {

    /** {@code DATE NEEDED: 5/11/2022}, however it is punctuated, up to the end of the line or a {@code *}. */
    private static final Pattern DATE_NEEDED =
            Pattern.compile("(?i)date\\s*needed\\s*[:-]?\\s*([^\\n*]{1,40})");

    /** The searchable half of the "sent" mark. Must match the search in {@link ShopifyGraphQL#SUPPLIER_PENDING_ORDERS}. */
    static final String SENT_TAG = "pacesetter-enviado";
    static final String ORDER_GID_PREFIX = "gid://shopify/Order/";
    /** The one blocker a deliberate resend is allowed to overrule. */
    static final String ALREADY_SENT = "Already sent to PaceSetter.";
    /** Pages of 50 open orders read before giving up; a store is not expected to have more unsent ones. */
    private static final int MAX_PAGES = 10;

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ShopifyGraphQLClient gql;
    private final SupplierOrderEmail email;
    private final SupplierOrderMailer mailer;
    private final SupplierOrderProperties props;

    public SupplierOrderService(ShopifyGraphQLClient gql, SupplierOrderEmail email,
                                SupplierOrderMailer mailer, SupplierOrderProperties props) {
        this.gql = gql;
        this.email = email;
        this.mailer = mailer;
        this.props = props;
    }

    /**
     * Everything the PO would carry, and why it cannot go yet.
     *
     * @param blocking reasons the order cannot be sent; empty means it could
     * @param notices  worth knowing, never blocking (a test order, a line with no text to engrave)
     * @param sent     the recorded send (the metafield's JSON), null when never sent
     */
    public record Preview(String orderId, String orderName, String poNumber, String createdAt, boolean test,
                          String financialStatus, String fulfillmentStatus, String note, String dateNeeded,
                          ShipTo shipTo, String shippingMethod, List<Line> lines, List<ExcludedLine> excluded,
                          List<String> blocking, List<String> notices, String sent) {

        /** Serialized as {@code ready}. */
        public boolean isReady() {
            return blocking.isEmpty();
        }
    }

    /**
     * One PaceSetter line.
     *
     * @param partId          PaceSetter's item number; null when the variant does not say (blocks)
     * @param quantity        what is still to fulfil — what the PO asks for
     * @param orderedQuantity what the customer ordered
     * @param personalization the line's visible properties (Easify's "Line 1", "Engraving Style", …),
     *                        in the order the store shows them; keys differ from product to product
     */
    public record Line(String partId, String title, String variantTitle, String sku, int quantity,
                       int orderedQuantity, BigDecimal unitPrice, String currency,
                       Map<String, String> personalization) {
    }

    public record ExcludedLine(String title, String sku, int quantity, String reason) {
    }

    public record ShipTo(String name, String company, String address1, String address2, String city,
                         String province, String provinceCode, String zip, String country,
                         String countryCode, String phone) {
    }

    /** A row of the "to send" list: enough to pick the order, the rest is in its preview. */
    public record PendingOrder(String orderId, String orderName, String createdAt, boolean test,
                               String financialStatus, String destination, List<LineSummary> lines,
                               int otherLines, List<String> blocking) {
    }

    public record LineSummary(String partId, String title, int quantity) {
    }

    /** @return the preview, or empty when no order has that id */
    public Optional<Preview> preview(String orderId) {
        JsonNode order = gql.execute(ShopifyGraphQL.SUPPLIER_ORDER_BY_ID, Map.of("id", orderGid(orderId)))
                .path("order");
        return order.isMissingNode() || order.isNull() ? Optional.empty() : Optional.of(toPreview(order));
    }

    /** Open, unsent orders with at least one PaceSetter line left to fulfil, newest first. */
    public List<PendingOrder> pending() {
        List<PendingOrder> pending = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> vars = new LinkedHashMap<>();
            if (cursor != null) {
                vars.put("cursor", cursor);
            }
            JsonNode orders = gql.execute(ShopifyGraphQL.SUPPLIER_PENDING_ORDERS, vars).path("orders");
            for (JsonNode order : orders.path("nodes")) {
                Preview p = toPreview(order);
                // The search cannot see the metafield, and a cancelled order can still be "open".
                if (p.sent() != null || isCancelled(order) || p.lines().isEmpty()) {
                    continue;
                }
                pending.add(new PendingOrder(p.orderId(), p.orderName(), p.createdAt(), p.test(),
                        p.financialStatus(), destination(p.shipTo()),
                        p.lines().stream().map(l -> new LineSummary(l.partId(), l.title(), l.quantity())).toList(),
                        p.excluded().size(), p.blocking()));
            }
            JsonNode pageInfo = orders.path("pageInfo");
            if (!pageInfo.path("hasNextPage").asBoolean(false)) {
                break;
            }
            cursor = pageInfo.path("endCursor").asText(null);
        }
        return pending;
    }

    /**
     * What one send did.
     *
     * @param marked    whether the order now carries the mark that keeps it from going twice
     * @param markError why it does not, when the email went out but the mark failed — reported, never
     *                  thrown: throwing would invite a retry, and a retry would send the PO again
     */
    public record SendResult(String orderId, String orderName, String poNumber, String to, String cc,
                             String bcc, String sentAt, int lines, boolean marked, String markError) {
    }

    /**
     * Emails the PO to PaceSetter and marks the order as sent.
     *
     * <p>Refuses (never half-sends) when the order is not ready, when it has been sent before and
     * {@code resend} was not asked for, or when the mailbox is not configured. Sends first and marks
     * after: a mark that fails leaves a visible problem, while a send that fails after a mark would
     * leave an order nobody knows was never ordered.
     *
     * @param recipients addresses for this one send; blank fields keep the configured ones. What was
     *                   actually used is what the sent record stores — a PO that went somewhere else
     *                   has to say so.
     */
    public SendResult send(Preview order, boolean resend, SupplierOrderEmail.Recipients recipients) {
        List<String> stopping = new ArrayList<>(order.blocking());
        if (resend) {
            stopping.remove(ALREADY_SENT);
        }
        if (!stopping.isEmpty()) {
            throw new SupplierOrderRefusedException("This order cannot be sent to PaceSetter: "
                    + String.join(" ", stopping));
        }
        if (!props.isEnabled()) {
            throw new SupplierOrderRefusedException("Sending to PaceSetter is off: set "
                    + "orders.pacesetter.enabled=true (PACESETTER_ORDERS_ENABLED) once the mailbox is configured.");
        }
        SupplierOrderEmail.EmailPreview mail = email.render(order, recipients);
        // The renderer repeats the order's own blockers; a resend has just overruled one of them, so
        // only what is missing from the CONFIGURATION can still stop the send here.
        List<String> unconfigured = mail.missing().stream()
                .filter(m -> !order.blocking().contains(m)).toList();
        if (!unconfigured.isEmpty()) {
            throw new SupplierOrderRefusedException(String.join(" ", unconfigured));
        }

        mailer.send(mail);
        String sentAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        String markError = null;
        try {
            mark(order, mail, sentAt);
        } catch (RuntimeException e) {
            markError = e.getMessage();
            log.error("Order {} was emailed to PaceSetter but could not be marked as sent — mark it by "
                    + "hand (tag {}) before anyone sends it again: {}", order.orderName(), SENT_TAG, markError);
        }
        return new SendResult(order.orderId(), order.orderName(), order.poNumber(), mail.to(), mail.cc(),
                mail.bcc(), sentAt, order.lines().size(), markError == null, markError);
    }

    /** The record of what went (a metafield) and the flag the pending search reads (a tag). */
    private void mark(Preview order, SupplierOrderEmail.EmailPreview mail, String sentAt) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("po", order.poNumber());
        record.put("sentAt", sentAt);
        record.put("to", mail.to());
        record.put("cc", mail.cc());
        record.put("bcc", mail.bcc());
        record.put("lines", order.lines().stream()
                .map(l -> Map.of("partId", String.valueOf(l.partId()), "quantity", l.quantity())).toList());
        String value;
        try {
            value = JSON.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the sent record for " + order.orderName(), e);
        }
        checkUserErrors(gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields", List.of(Map.of(
                "ownerId", order.orderId(),
                "namespace", "trophy_sync",
                "key", "pacesetter_po",
                "type", "json",
                "value", value)))).path("metafieldsSet"), "metafieldsSet");
        checkUserErrors(gql.execute(ShopifyGraphQL.TAGS_ADD,
                Map.of("id", order.orderId(), "tags", List.of(SENT_TAG))).path("tagsAdd"), "tagsAdd");
    }

    private static void checkUserErrors(JsonNode payload, String operation) {
        JsonNode errors = payload.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException(operation + " userErrors: " + errors);
        }
    }

    /** Accepts the numeric id the console and the admin link carry, or a full order GID. */
    static String orderGid(String orderId) {
        String id = orderId == null ? "" : orderId.trim();
        if (id.startsWith(ORDER_GID_PREFIX)) {
            id = id.substring(ORDER_GID_PREFIX.length());
        }
        if (!id.matches("\\d+")) {
            throw new IllegalArgumentException("Not a Shopify order id: " + orderId);
        }
        return ORDER_GID_PREFIX + id;
    }

    Preview toPreview(JsonNode order) {
        List<Line> lines = new ArrayList<>();
        List<ExcludedLine> excluded = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        for (JsonNode item : order.path("lineItems").path("nodes")) {
            String partId = text(item.path("variant").path("vendorSku").path("value"));
            boolean supplierProduct = text(item.path("product").path("psId").path("value")) != null;
            String title = text(item.path("title"));
            int ordered = item.path("quantity").asInt(0);
            int open = item.path("unfulfilledQuantity").asInt(0);
            if (partId == null && !supplierProduct) {
                excluded.add(new ExcludedLine(title, text(item.path("sku")), ordered, "not a PaceSetter product"));
                continue;
            }
            if (open == 0) {
                excluded.add(new ExcludedLine(title, text(item.path("sku")), ordered, "already fulfilled"));
                continue;
            }
            JsonNode money = item.path("originalUnitPriceSet").path("shopMoney");
            Map<String, String> personalization = personalization(item.path("customAttributes"));
            lines.add(new Line(partId, title, text(item.path("variantTitle")), text(item.path("sku")), open,
                    ordered, money.hasNonNull("amount") ? new BigDecimal(money.path("amount").asText()) : null,
                    text(money.path("currencyCode")), personalization));
            if (partId == null) {
                blocking.add(title + ": a PaceSetter product whose variant has no trophy_sync.vendor_sku, so "
                        + "nothing says which part to order. Sync the product, then reload.");
            }
            if (open < ordered) {
                notices.add(title + ": " + (ordered - open) + " of " + ordered + " already fulfilled; the PO asks for the "
                        + open + " left.");
            }
            if (personalization.isEmpty()) {
                notices.add(title + ": no text to engrave on the line.");
            }
        }

        String sent = text(order.path("sent").path("value"));
        if (sent != null || hasTag(order, SENT_TAG)) {
            blocking.add(ALREADY_SENT);
        }
        if (isCancelled(order)) {
            blocking.add("The order is cancelled.");
        }
        if (lines.isEmpty()) {
            blocking.add("No PaceSetter line left to send.");
        }
        ShipTo shipTo = shipTo(order.path("shippingAddress"));
        if (shipTo == null && !lines.isEmpty()) {
            blocking.add("The order has no shipping address.");
        }
        if (order.path("test").asBoolean(false)) {
            notices.add("Test order.");
        }
        String financial = text(order.path("displayFinancialStatus"));
        if (financial != null && !"PAID".equals(financial)) {
            notices.add("Payment status is " + financial.toLowerCase().replace('_', ' ') + ".");
        }

        String name = text(order.path("name"));
        String note = text(order.path("note"));
        String dateNeeded = dateNeeded(order, note);
        if (dateNeeded == null) {
            notices.add("No date the customer needs it by: the PO will not ask for one.");
        }
        return new Preview(text(order.path("id")), name, poNumber(name), text(order.path("createdAt")),
                order.path("test").asBoolean(false), financial, text(order.path("displayFulfillmentStatus")),
                note, dateNeeded, shipTo, text(order.path("shippingLine").path("title")),
                lines, excluded, blocking, notices, sent);
    }

    /**
     * When the customer needs it. Shopify models no such field, so it arrives wherever the shop puts
     * it: an order or line property whose name mentions a date, or — as every migrated order has it —
     * inside the note ({@code ***** DATE NEEDED: 5/11/2022 *****}). Null when nobody said, which is a
     * notice rather than a blocker: PaceSetter quotes 9 working days when no date is given.
     */
    static String dateNeeded(JsonNode order, String note) {
        String fromAttributes = dateAttribute(order.path("customAttributes"));
        if (fromAttributes != null) {
            return fromAttributes;
        }
        for (JsonNode item : order.path("lineItems").path("nodes")) {
            String perLine = dateAttribute(item.path("customAttributes"));
            if (perLine != null) {
                return perLine;
            }
        }
        if (note != null) {
            Matcher m = DATE_NEEDED.matcher(note);
            if (m.find()) {
                String value = m.group(1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String dateAttribute(JsonNode attributes) {
        for (JsonNode a : attributes) {
            String key = text(a.path("key"));
            String value = text(a.path("value"));
            if (key != null && value != null && !key.startsWith("_")
                    && key.toLowerCase(Locale.ROOT).contains("date")) {
                return value;
            }
        }
        return null;
    }

    /** The PO number is the order's name without its {@code #}, which is what joins PaceSetter's answers back to it. */
    static String poNumber(String orderName) {
        return orderName == null ? null : orderName.startsWith("#") ? orderName.substring(1) : orderName;
    }

    /** Line properties whose key starts with {@code _} are the app's own bookkeeping (Easify's {@code _tpo_add_by}), not the shopper's. */
    private static Map<String, String> personalization(JsonNode attributes) {
        Map<String, String> visible = new LinkedHashMap<>();
        for (JsonNode a : attributes) {
            String key = text(a.path("key"));
            String value = text(a.path("value"));
            if (key != null && !key.startsWith("_") && value != null) {
                visible.put(key, value);
            }
        }
        return visible;
    }

    private static ShipTo shipTo(JsonNode a) {
        if (a.isMissingNode() || a.isNull()) {
            return null;
        }
        return new ShipTo(text(a.path("name")), text(a.path("company")), text(a.path("address1")),
                text(a.path("address2")), text(a.path("city")), text(a.path("province")),
                text(a.path("provinceCode")), text(a.path("zip")), text(a.path("country")),
                text(a.path("countryCodeV2")), text(a.path("phone")));
    }

    /** City and state for the list: where it goes, without putting the customer's address in every row. */
    private static String destination(ShipTo shipTo) {
        if (shipTo == null) {
            return null;
        }
        String region = shipTo.provinceCode() != null ? shipTo.provinceCode() : shipTo.province();
        return shipTo.city() == null ? region : region == null ? shipTo.city() : shipTo.city() + ", " + region;
    }

    private static boolean isCancelled(JsonNode order) {
        return text(order.path("cancelledAt")) != null;
    }

    private static boolean hasTag(JsonNode order, String tag) {
        for (JsonNode t : order.path("tags")) {
            if (tag.equalsIgnoreCase(t.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText();
        return s.isBlank() ? null : s;
    }
}
