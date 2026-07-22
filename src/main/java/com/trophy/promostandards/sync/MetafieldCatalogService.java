package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.sync.model.MetafieldDefinitionView;
import com.trophy.promostandards.sync.model.MetafieldSample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalog of the store's existing product metafields, so the import UI can list the
 * metafields other products already use, let the user pick some, and copy an example value.
 *
 * <p>The app-managed identity keys ({@code ps_supplier}, {@code ps_product_id},
 * {@code ps_price_breaks}) are filtered out: they are always stamped automatically, so offering them
 * for manual selection would only confuse.
 */
@Service
public class MetafieldCatalogService {

    /** Keys the importer always writes itself — never offered for manual selection. */
    private static final Set<String> MANAGED_KEYS = Set.of(
            ShopifyProductMapper.MF_SUPPLIER, ShopifyProductMapper.MF_PRODUCT_ID,
            ShopifyProductMapper.MF_PRICE_BREAKS);

    private final ShopifyGraphQLClient gql;

    public MetafieldCatalogService(ShopifyGraphQLClient gql) {
        this.gql = gql;
    }

    /** @return every product metafield definition in the store, minus the app-managed identity keys. */
    public List<MetafieldDefinitionView> listProductDefinitions() {
        List<MetafieldDefinitionView> definitions = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> vars = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode defs = require(gql.execute(ShopifyGraphQL.METAFIELD_DEFINITIONS, vars))
                    .path("metafieldDefinitions");
            for (JsonNode node : defs.path("nodes")) {
                String namespace = node.path("namespace").asText(null);
                String key = node.path("key").asText(null);
                if (key == null || ("custom".equals(namespace) && MANAGED_KEYS.contains(key))) {
                    continue;
                }
                definitions.add(new MetafieldDefinitionView(
                        namespace, key,
                        node.path("name").asText(null),
                        node.path("type").path("name").asText(null),
                        node.path("description").asText(null)));
            }
            JsonNode pageInfo = defs.path("pageInfo");
            cursor = pageInfo.path("hasNextPage").asBoolean(false)
                    ? pageInfo.path("endCursor").asText(null) : null;
        } while (cursor != null);
        return definitions;
    }

    /**
     * Sample products and return the non-null values found for one metafield, so the user can copy an
     * existing value. Returns at most {@code limit} examples.
     */
    public List<MetafieldSample> sampleValues(String namespace, String key, int limit) {
        int pageSize = Math.max(limit, 1) * 5; // over-sample: many products won't have the metafield
        JsonNode data = require(gql.execute(ShopifyGraphQL.METAFIELD_SAMPLES,
                Map.of("limit", pageSize, "namespace", namespace, "key", key)));
        List<MetafieldSample> samples = new ArrayList<>();
        for (JsonNode node : data.path("products").path("nodes")) {
            String value = node.path("metafield").path("value").asText(null);
            if (value != null && !value.isBlank()) {
                samples.add(new MetafieldSample(node.path("title").asText(null), value));
                if (samples.size() >= limit) {
                    break;
                }
            }
        }
        return samples;
    }

    private static JsonNode require(JsonNode data) {
        if (data == null) {
            throw new ShopifySyncException("Shopify returned no data");
        }
        return data;
    }
}
