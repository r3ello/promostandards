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
 * <p>Variants are the <b>union</b> of the (colour, size) combinations seen in Product Data (parts ×
 * sizes) and in the Inventory service (per-variation rows) — suppliers populate these inconsistently,
 * so taking only one source loses variants (and inventory). Each variant's price is resolved from the
 * Pricing matrix (by the colour's part id, falling back to a single product-level price); the full
 * price-break matrix is preserved on {@link SupplierProduct#priceParts()} for the metafield.
 */
@Service
public class CatalogService {

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

        Product product = productData.getProduct(productId, country, language);
        // Net + the supplier's published retail, so PricingPolicy can prefer the latter.
        Configuration config = pricing.getConfigurationAndPricingWithList(
                productId, props.currency(), null, null, country, language);
        InventoryLevels levels = inventory.getInventoryLevels(productId, null);

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
        for (MediaContent m : media.getMediaContent(productId, "Image", null)) {
            if (m.url() == null || m.url().isBlank()) {
                continue;
            }
            gallery.add(m.url());
            String color = m.partId() == null ? null : colorByPart.get(m.partId());
            if (color != null) {
                imagesByColor.computeIfAbsent(color, c -> new ArrayList<>()).add(m.url());
            }
        }

        // Union variant keys: Product Data parts × sizes, then Inventory rows.
        Map<String, VariantAcc> accs = new LinkedHashMap<>();
        for (Product.ProductPart part : product.parts()) {
            String color = norm(part.primaryColor());
            List<String> sizes = part.sizes() == null || part.sizes().isEmpty()
                    ? java.util.Collections.singletonList(null) : part.sizes();
            for (String size : sizes) {
                VariantAcc acc = accs.computeIfAbsent(key(color, norm(size)), k -> new VariantAcc());
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
            VariantAcc acc = accs.computeIfAbsent(key(color, size), k -> new VariantAcc());
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
        List<VariantAcc> phantoms = accs.values().stream()
                .filter(a -> a.onHand == null && accs.values().stream().anyMatch(b -> refines(b, a)))
                .toList();
        accs.values().removeAll(phantoms);

        // Resolve each variant: price (colour part id -> single fallback), SKU, images.
        List<Variant> variants = new ArrayList<>();
        for (VariantAcc acc : accs.values()) {
            String colorPart = partIdByColor.getOrDefault(acc.color, acc.partId);
            PartPriceRef price = priceByPart.get(colorPart);
            if (price == null) {
                price = priceByPart.getOrDefault(acc.partId, singlePrice);
            }
            String partId = colorPart != null ? colorPart : acc.partId;
            variants.add(new Variant(partId, acc.color, acc.size, sku(partId, acc.size),
                    price == null ? null : price.net(), price == null ? null : price.list(),
                    acc.onHand, imagesByColor.getOrDefault(acc.color, List.of())));
        }

        List<String> distinctGallery = new ArrayList<>(new LinkedHashSet<>(gallery));
        List<String> tags = product.categories() == null ? List.of() : product.categories();
        String productType = tags.isEmpty() ? null : tags.get(tags.size() - 1);

        return new SupplierProduct(productId, product.productName(), product.description(),
                norm(product.productBrand()), productType, tags, variants, distinctGallery, config.partPrices());
    }

    private String sku(String partId, String size) {
        if (props.skuStrategy() == SyncProperties.SkuStrategy.PART || size == null || size.isBlank()) {
            return partId;
        }
        return partId + "-" + size;
    }

    private static String key(String color, String size) {
        return (color == null ? "" : color) + " " + (size == null ? "" : size);
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
