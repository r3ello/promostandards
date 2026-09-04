package com.trophy.promostandards.sync;

import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.service.MediaService;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates the PromoStandards services into a single {@link SupplierProduct} for the Shopify import.
 *
 * <p>Variants are the <b>union</b> of the (part id, size) combinations seen in Product Data (parts ×
 * sizes) and in the Inventory service (per-variation rows) — suppliers populate these inconsistently,
 * so taking only one source loses variants (and inventory). Each variant's price is resolved from the
 * Pricing matrix (by the colour's part id, falling back to a single product-level price); the full
 * price-break matrix is preserved on {@link SupplierProduct#priceParts()} for the metafield.
 */
@Service
public class CatalogService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CatalogService.class);

    private final ProductDataService productData;
    private final PricingService pricing;
    private final InventoryService inventory;
    private final MediaService media;
    private final SyncProperties props;

    public CatalogService(ProductDataService productData, PricingService pricing,
                          InventoryService inventory, MediaService media, SyncProperties props) {
        this.productData = productData;
        this.pricing = pricing;
        this.inventory = inventory;
        this.media = media;
        this.props = props;
    }

    /** Price lookup for a part: lowest-break net + list, kept null-safe. */
    private record PartPriceRef(BigDecimal net, BigDecimal list) {
    }

    /** Mutable accumulator while unioning variants from Product Data and Inventory. */
    private static final class VariantAcc {
        String color;
        String size;
        String partId;       // colour-level part id (prefer Product Data) for pricing/media/SKU
        Integer onHand;
    }

    public SupplierProduct aggregate(String productId) {
        String country = props.country();
        String language = props.language();

        List<String> warnings = new ArrayList<>();

        // Product Data and Pricing are load-bearing: without parts there is no product to build, and
        // without prices we would publish one at no price at all. Inventory and Media are not — see
        // optional() for why a supplier that fails on those must not cost the whole import.
        Product product = productData.getProduct(productId, country, language);
        // Net + the supplier's published retail, so PricingPolicy can prefer the latter.
        Configuration config = pricing.getConfigurationAndPricingWithList(
                productId, props.currency(), null, null, country, language);
        InventoryLevels levels = optional("Inventory", warnings,
                () -> inventory.getInventoryLevels(productId, null),
                new InventoryLevels(productId, List.of()),
                "variant stock is left as Shopify has it");
        List<MediaContent> mediaItems = optional("Media", warnings,
                () -> media.getMediaContent(productId, "Image", null), List.of(),
                "images are left as Shopify has them");

        // Pricing: lowest-break price per part id, plus the single-entry fallback.
        Map<String, PartPriceRef> priceByPart = new LinkedHashMap<>();
        for (Configuration.PartPrice pp : config.partPrices()) {
            pp.priceBreaks().stream()
                    .min(Comparator.comparingInt(Configuration.PriceBreak::minQuantity))
                    .ifPresent(b -> priceByPart.put(pp.partId(), new PartPriceRef(b.price(), b.listPrice())));
        }
        PartPriceRef singlePrice = priceByPart.size() == 1 ? priceByPart.values().iterator().next() : null;

        // Colour -> part id (from Product Data) for pricing/media/SKU resolution.
        Map<String, String> partIdByColor = new LinkedHashMap<>();
        for (Product.ProductPart part : product.parts()) {
            partIdByColor.putIfAbsent(norm(part.primaryColor()), part.partId());
        }

        // Media: image URLs per colour (+ product gallery).
        Map<String, String> colorByPart = new LinkedHashMap<>();
        for (Product.ProductPart part : product.parts()) {
            colorByPart.put(part.partId(), norm(part.primaryColor()));
        }
        Map<String, List<String>> imagesByColor = new LinkedHashMap<>();
        List<String> gallery = new ArrayList<>();
        for (MediaContent m : mediaItems) {
            if (m.url() == null || m.url().isBlank()) {
                continue;
            }
            gallery.add(m.url());
            String color = m.partId() == null ? null : colorByPart.get(m.partId());
            if (color != null) {
                imagesByColor.computeIfAbsent(color, c -> new ArrayList<>()).add(m.url());
            }
        }

        // Union the variants: Product Data parts × sizes first, then Inventory rows folded into them.
        List<VariantAcc> accs = new ArrayList<>();
        Map<String, VariantAcc> byColorSize = new LinkedHashMap<>();
        for (Product.ProductPart part : product.parts()) {
            String color = norm(part.primaryColor());
            List<String> sizes = part.sizes() == null || part.sizes().isEmpty()
                    ? java.util.Collections.singletonList(null) : part.sizes();
            for (String size : sizes) {
                VariantAcc acc = byColorSize.computeIfAbsent(key(color, norm(size)), k -> {
                    VariantAcc fresh = new VariantAcc();
                    accs.add(fresh);
                    return fresh;
                });
                acc.color = color;
                acc.size = norm(size);
                if (acc.partId == null) {
                    acc.partId = part.partId();
                }
            }
        }
        for (InventoryLevels.PartInventory pi : levels.parts()) {
            String color = norm(pi.color());
            String size = norm(pi.size());
            VariantAcc acc = forInventoryRow(accs, pi.partId(), color, size);
            if (acc == null) {
                acc = new VariantAcc();
                accs.add(acc);
            }
            acc.color = color;
            acc.size = size;
            acc.onHand = pi.quantityAvailable();
            if (acc.partId == null) {
                acc.partId = pi.partId();
            }
        }

        // Drop phantom variants: a Product-Data-only row that adds no colour/size beyond an
        // inventory row for the same part id is the same physical part, not another variant —
        // PaceSetter puts placeholder colours (N/A) and no sizes on parts while the real
        // colour/size lives only on the inventory row. Keeping it would import a zero-stock
        // duplicate (e.g. "N/A / One Size") next to the real variant.
        List<VariantAcc> phantoms = accs.stream()
                .filter(a -> a.onHand == null && accs.stream().anyMatch(b -> refines(b, a)))
                .toList();
        accs.removeAll(phantoms);

        List<String> distinctGallery = new ArrayList<>(new LinkedHashSet<>(gallery));

        // Resolve each variant: price (colour part id -> single fallback), SKU, images.
        List<Variant> variants = new ArrayList<>();
        for (VariantAcc acc : accs) {
            // Identity is the variant's own part id; the colour's Product Data part is only a
            // fallback for looking up a price (and for parts the Inventory service did not name).
            String colorPart = partIdByColor.getOrDefault(acc.color, acc.partId);
            PartPriceRef price = priceByPart.get(acc.partId);
            if (price == null) {
                price = priceByPart.getOrDefault(colorPart, singlePrice);
            }
            String partId = acc.partId != null ? acc.partId : colorPart;
            // PaceSetter never sets partId on its media, but it does serve each colour as a product
            // of its own with its own photo: getMediaContent(CM373LB) answers cm373lb.jpg. So the
            // gallery of THIS call belongs to the variant of the id that was asked for — and only to
            // that one, or CM373BS's photo would end up on all eleven colours of the family.
            List<String> variantImages = imagesByColor.getOrDefault(acc.color, List.of());
            if (variantImages.isEmpty() && partId != null && partId.equalsIgnoreCase(productId)) {
                variantImages = distinctGallery;
            }
            variants.add(new Variant(partId, acc.color, acc.size, sku(partId, acc.size),
                    price == null ? null : price.net(), price == null ? null : price.list(),
                    acc.onHand, variantImages));
        }

        List<String> tags = product.categories() == null ? List.of() : product.categories();
        String productType = tags.isEmpty() ? null : tags.get(tags.size() - 1);

        return new SupplierProduct(productId, product.productName(), product.description(),
                norm(product.productBrand()), productType, tags, variants, distinctGallery,
                config.partPrices(), List.copyOf(warnings));
    }

    /**
     * Calls a service the import can live without, and turns a failure into a warning.
     *
     * <p>The first full pass over the store found the reason: PaceSetter <em>sells</em> products its
     * Inventory service answers "ProductID not found" for (13 of them), and its Media service faults
     * outright on the whole GM8xx family — and each of those took the entire import down with it,
     * variants, prices and discounts included, for data that was otherwise complete.
     *
     * <p>Dropping the answer is safe in both directions because "missing" already has a meaning
     * downstream: a variant with a null {@code onHand} is skipped by every inventory push (so an
     * outage can never zero real stock), and an empty image list is simply not sent (so it can never
     * clear a product's images). What is lost is an update, never data.
     */
    private <T> T optional(String service, List<String> warnings, java.util.function.Supplier<T> call,
                           T fallback, String consequence) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("{} unavailable, continuing without it: {}", service, e.getMessage());
            warnings.add(service + ": " + e.getMessage() + " — " + consequence);
            return fallback;
        }
    }

    private String sku(String partId, String size) {
        if (props.skuStrategy() == SyncProperties.SkuStrategy.PART || size == null || size.isBlank()) {
            return partId;
        }
        return partId + "-" + size;
    }

    private static String key(String color, String size) {
        return (color == null ? "" : color.toUpperCase(Locale.ROOT)) + " "
                + (size == null ? "" : size.toUpperCase(Locale.ROOT));
    }

    /**
     * The variant an Inventory row belongs to, or {@code null} when it is a variant of its own.
     *
     * <p>Sources disagree on granularity, so this cannot be a plain (colour, size) lookup. A supplier
     * may report inventory per size against a colour-level part ({@code SAMPLE-001-RED} → rows
     * {@code SAMPLE-001-RED-S}), which must fold into the Product Data row; and PaceSetter reuses one
     * colour across the parts of a family — {@code CM297BL} and {@code CM297LB} are <em>both</em>
     * "Dark Brown / 12 X 9.5" — which must not, or the two parts merge into one variant and their
     * stock mixes. So: the same part id wins, then an unclaimed Product Data row for the same
     * colour and size; a row that claims neither is its own variant.
     */
    private static VariantAcc forInventoryRow(List<VariantAcc> accs, String partId, String color,
                                              String size) {
        for (VariantAcc a : accs) {
            if (a.partId != null && a.partId.equalsIgnoreCase(partId)
                    && (a.size == null || size == null || a.size.equalsIgnoreCase(size))) {
                return a;
            }
        }
        for (VariantAcc a : accs) {
            if (a.onHand == null && sameValue(a.color, color) && sameValue(a.size, size)) {
                return a;
            }
        }
        return null;
    }

    private static boolean sameValue(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /** true when inventory-backed {@code b} carries at least {@code a}'s colour/size for the same part. */
    private static boolean refines(VariantAcc b, VariantAcc a) {
        return b != a && b.onHand != null
                && a.partId != null && a.partId.equals(b.partId)
                && (a.color == null || a.color.equalsIgnoreCase(b.color))
                && (a.size == null || a.size.equalsIgnoreCase(b.size));
    }

    /** Supplier placeholder tokens meaning "no value" (PaceSetter sends literal N/A and NULL). */
    private static final Set<String> PLACEHOLDERS = Set.of("n/a", "null", "none");

    /**
     * Normalise an attribute for joining: trim, and treat blank or a placeholder token as null so
     * sources align (e.g. Product Data says colour "N/A" while Inventory says "Clear").
     */
    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() || PLACEHOLDERS.contains(t.toLowerCase(Locale.ROOT)) ? null : t;
    }
}
