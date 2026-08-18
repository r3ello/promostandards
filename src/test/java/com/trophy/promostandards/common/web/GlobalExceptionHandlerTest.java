package com.trophy.promostandards.common.web;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    /**
     * "The supplier has no record for this id" is a 404 — the supplier answered fine. It must not be
     * swallowed by the broader {@link PromoStandardsClientException} handler, whose 502 wrongly reads
     * as a supplier outage (this is what a listed-but-unknown id like GI840 used to report).
     */
    @Test
    void resolvesNotFoundToItsOwnHandlerRatherThanTheUpstreamFailureOne() {
        assertThat(resolver.resolveMethod(new PromoStandardsNotFoundException("no product")).getName())
                .isEqualTo("handleNotFound");
        assertThat(resolver.resolveMethod(new PromoStandardsClientException("timeout")).getName())
                .isEqualTo("handleClientException");
    }

    @Test
    void mapsTheTwoSupplierOutcomesToTheirStatuses() {
        assertThat(handler.handleNotFound(new PromoStandardsNotFoundException("no product"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleClientException(new PromoStandardsClientException("timeout"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
