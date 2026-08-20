package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link CatalogService} against the default in-memory PromoStandards stub clients,
 * verifying the cross-service join (part × size variants, color-level price, (color,size) inventory,
 * per-color media). Loading the full context also asserts every new sync bean wires up.
 */
@SpringBootTest
class CatalogServiceTest {

    @Autowired
    private CatalogService catalog;

    @Test
    void aggregatesProductAcrossServices() {
        SupplierProduct product = catalog.aggregate("SAMPLE-001");

        assertThat(product.title()).isEqualTo("Sample Polo Shirt");
        assertThat(product.vendor()).isEqualTo("Trophy Apparel");
        assertThat(product.productType()).isEqualTo("Polos");
        assertThat(product.tags()).containsExactly("Apparel", "Polos");

        // Red {S,M,L,XL} + Blue {S,M,L} = 7 variants
        assertThat(product.variants()).hasSize(7);

        Variant redS = variant(product, "SAMPLE-001-RED-S");
        assertThat(redS.color()).isEqualTo("Red");
        assertThat(redS.size()).isEqualTo("S");
        assertThat(redS.supplierNet()).isEqualByComparingTo(new BigDecimal("9.50")); // lowest break
        // The supplier's own suggested retail, fetched with priceType=List (net 9.50 / 0.60).
        assertThat(redS.listPrice()).isEqualByComparingTo(new BigDecimal("15.83"));
        assertThat(redS.onHand()).isEqualTo(1200);
        assertThat(redS.imageUrls()).containsExactly("https://cdn.example.com/SAMPLE-001/red-front.jpg");

        // Inventory only covered Red-S, Red-M, Blue-S; others are unknown (null), not zero.
        assertThat(variant(product, "SAMPLE-001-RED-M").onHand()).isEqualTo(350);
        assertThat(variant(product, "SAMPLE-001-BLU-S").onHand()).isEqualTo(0);
        assertThat(variant(product, "SAMPLE-001-RED-L").onHand()).isNull();

        // Document media is filtered out; only the two color images form the gallery.
        assertThat(product.imageUrls()).containsExactlyInAnyOrder(
                "https://cdn.example.com/SAMPLE-001/red-front.jpg",
                "https://cdn.example.com/SAMPLE-001/blue-front.jpg");
    }

    private static Variant variant(SupplierProduct product, String sku) {
        return product.variants().stream().filter(v -> sku.equals(v.sku())).findFirst().orElseThrow();
    }
}
