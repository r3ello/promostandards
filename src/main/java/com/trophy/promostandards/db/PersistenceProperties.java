package com.trophy.promostandards.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Master switch for Postgres persistence ({@code sync.persistence.*}).
 *
 * <p>Off by default, which is the pre-database behaviour: catalog indexes in memory and on disk
 * ({@code data/*.json}), and a scheduler that re-pushes every imported product. On, the catalog
 * mirror and the sync bookkeeping live in Postgres.
 *
 * <p>The flag is read twice by design: here as a normal property for application code, and directly
 * from the {@code Environment} by {@link PersistenceAutoConfigurationExcluder}, which has to decide
 * before any bean exists whether a {@code DataSource} should be auto-configured at all.
 *
 * @param enabled whether the database is used; rolling back is setting this false and restarting
 */
@ConfigurationProperties(prefix = "sync.persistence")
public record PersistenceProperties(@DefaultValue("false") boolean enabled) {
}
