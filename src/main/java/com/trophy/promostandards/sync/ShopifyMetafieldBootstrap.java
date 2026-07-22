package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ensures the {@code custom.ps_supplier} / {@code custom.ps_product_id} product metafield definitions
 * exist, so the identity metafields stamped on imported products are visible and filterable in the
 * Shopify admin.
 *
 * <p><b>Opt-in:</b> creating metafield <em>definitions</em> needs the {@code write_metafield_definitions}
 * scope, which most tokens don't have (and the sync works without it — metafield <em>values</em> are
 * still stamped during {@code productSet}). So this only runs when
 * {@code sync.ensure-metafield-definitions=true} <em>and</em> a store is configured. Failures are
 * logged, never fatal.
 */
@Component
@ConditionalOnProperty(prefix = "sync", name = "ensure-metafield-definitions", havingValue = "true")
public class ShopifyMetafieldBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopifyMetafieldBootstrap.class);

    private final ShopifyGraphQLClient gql;
    private final ShopifyProperties shopify;

    public ShopifyMetafieldBootstrap(ShopifyGraphQLClient gql, ShopifyProperties shopify) {
        this.gql = gql;
        this.shopify = shopify;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (shopify.storeDomain() == null || shopify.storeDomain().isBlank()) {
            return; // no store configured (tests / local without Shopify) — skip
        }
        ensureDefinition(ShopifyProductMapper.MF_SUPPLIER, "PromoStandards Supplier");
        ensureDefinition(ShopifyProductMapper.MF_PRODUCT_ID, "PromoStandards Product Id");
    }

    private void ensureDefinition(String key, String name) {
        Map<String, Object> definition = Map.of(
                "name", name,
                "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                "key", key,
                "type", "single_line_text_field",
                "ownerType", "PRODUCT");
        try {
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELD_DEFINITION_CREATE,
                    Map.of("definition", definition));
            JsonNode userErrors = data.path("metafieldDefinitionCreate").path("userErrors");
            if (userErrors.isArray() && !userErrors.isEmpty()
                    && !userErrors.toString().contains("TAKEN")) {
                log.warn("metafieldDefinitionCreate({}) userErrors: {}", key, userErrors);
            }
        } catch (RuntimeException e) {
            log.warn("Could not ensure metafield definition custom.{}: {}", key, e.getMessage());
        }
    }
}
