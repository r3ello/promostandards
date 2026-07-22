package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.model.MetafieldDefinitionView;
import com.trophy.promostandards.sync.model.MetafieldSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetafieldCatalogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Routes by the GraphQL operation name embedded in the query string. */
    private ShopifyHttp routingHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("ProductMetafieldDefinitions")) {
                response = """
                        {"data":{"metafieldDefinitions":{
                          "pageInfo":{"hasNextPage":false,"endCursor":null},
                          "nodes":[
                            {"id":"gid://shopify/MetafieldDefinition/1","name":"Country of origin","namespace":"custom","key":"country_of_origin","description":"Where it's made","type":{"name":"single_line_text_field","category":"TEXT"}},
                            {"id":"gid://shopify/MetafieldDefinition/2","name":"Supplier","namespace":"custom","key":"ps_supplier","description":null,"type":{"name":"single_line_text_field","category":"TEXT"}}
                          ]
                        }}}""";
            } else if (query.contains("MetafieldSamples")) {
                response = """
                        {"data":{"products":{"nodes":[
                          {"id":"gid://shopify/Product/1","title":"With value","handle":"a","metafield":{"value":"China","type":"single_line_text_field"}},
                          {"id":"gid://shopify/Product/2","title":"No value","handle":"b","metafield":null}
                        ]}}}""";
            } else {
                throw new IllegalStateException("unexpected query: " + query);
            }
            try {
                return MAPPER.readTree(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private MetafieldCatalogService service(ShopifyHttp http) {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        return new MetafieldCatalogService(new ShopifyGraphQLClient(http, tokens, shopify));
    }

    @Test
    void listsDefinitionsAndFiltersManagedKeys() {
        List<MetafieldDefinitionView> defs = service(routingHttp()).listProductDefinitions();

        // The app-managed custom.ps_supplier identity key is filtered out.
        assertThat(defs).hasSize(1);
        MetafieldDefinitionView d = defs.get(0);
        assertThat(d.namespace()).isEqualTo("custom");
        assertThat(d.key()).isEqualTo("country_of_origin");
        assertThat(d.name()).isEqualTo("Country of origin");
        assertThat(d.type()).isEqualTo("single_line_text_field");
    }

    @Test
    void sampleValuesKeepsOnlyNonNullValues() {
        List<MetafieldSample> samples = service(routingHttp())
                .sampleValues("custom", "country_of_origin", 5);

        assertThat(samples).singleElement().satisfies(s -> {
            assertThat(s.productTitle()).isEqualTo("With value");
            assertThat(s.value()).isEqualTo("China");
        });
    }
}
