package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The email that says what each scheduled sync did, bound from {@code sync.report.*}. Off by
 * default: it sends through the same SMTP mailbox as the PaceSetter orders.
 *
 * <p>Price passes run once a night, so each one is reported. Inventory runs every two hours, and an
 * email per pass would be twelve a day saying "nothing changed": a pass is reported on its own only
 * when something failed, and everything else goes in one daily digest.
 *
 * @param enabled               master switch ({@code SYNC_REPORT_ENABLED})
 * @param to                    recipients, comma-separated ({@code SYNC_REPORT_TO})
 * @param cc                    copied, comma-separated; blank for none
 * @param from                  the sender; blank uses the SMTP mailbox's own username
 * @param inventoryDigestCron   when the daily inventory digest goes out
 * @param inventoryEveryPass    also email every inventory pass, not only failed ones
 * @param timeZone              the zone times are written in — the shop's, not the server's (a
 *                              container runs in UTC)
 */
@ConfigurationProperties(prefix = "sync.report")
public record SyncReportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String to,
        @DefaultValue("") String cc,
        @DefaultValue("") String from,
        @DefaultValue("0 0 7 * * *") String inventoryDigestCron,
        @DefaultValue("false") boolean inventoryEveryPass,
        @DefaultValue("America/New_York") String timeZone
) {
}
