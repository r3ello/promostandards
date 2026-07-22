package com.trophy.promostandards.sync;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling so {@link SyncScheduler} (and the order sync job) fire on their cron
 * expressions. The jobs themselves are gated on {@code sync.schedule.enabled}.
 */
@Configuration
@EnableScheduling
public class SyncSchedulingConfig {
}
