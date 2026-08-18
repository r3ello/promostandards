package com.trophy.promostandards.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default install has no database, and must keep starting that way. This is the end-to-end guard
 * for {@link PersistenceAutoConfigurationExcluder}: it fails the moment the exclusion stops working,
 * which would otherwise only show up as a dead container on a machine with no {@code DB_URL}.
 */
@SpringBootTest
class PersistenceDisabledContextTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private PersistenceProperties props;

	@Test
	void startsWithNoDatabaseConfigured() {
		assertThat(props.enabled()).isFalse();
		assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
		// Flyway must be absent too: with a DataSource missing it would fail rather than skip.
		assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class)).isEmpty();
	}
}
