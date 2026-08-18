package com.trophy.promostandards.db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the application startable without a database.
 *
 * <p>Persistence is opt-in via {@code sync.persistence.enabled}. With the flag off, Spring Boot
 * would still auto-configure a {@code DataSource} — the Postgres driver is on the classpath — and
 * then fail startup with "Failed to configure a DataSource" because no URL is set. Excluding the
 * two entry-point auto-configurations avoids that, so an install with no database behaves exactly as
 * it did before persistence existed. This is what makes the rollback "set one variable and restart"
 * rather than "redeploy the previous image".
 *
 * <p>Only {@code DataSourceAutoConfiguration} and {@code FlywayAutoConfiguration} are listed: the
 * rest of the JDBC stack ({@code JdbcTemplate}, {@code JdbcClient}, the transaction manager) is
 * conditional on a {@code DataSource} bean and disables itself once that is gone.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} — registered in {@code META-INF/spring.factories} —
 * because the decision must be made before auto-configuration is evaluated.
 */
public class PersistenceAutoConfigurationExcluder implements EnvironmentPostProcessor {

	static final String ENABLED_PROPERTY = "sync.persistence.enabled";
	static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";
	static final String PROPERTY_SOURCE_NAME = "promostandards-persistence-disabled";

	private static final List<String> EXCLUDED_AUTO_CONFIGURATIONS = List.of(
			"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
			"org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
			return;
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(EXCLUDE_PROPERTY, String.join(",", merged(environment)));
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
	}

	/** Appends to any exclusions the application already declares instead of replacing them. */
	private static List<String> merged(ConfigurableEnvironment environment) {
		List<String> exclusions = new ArrayList<>();
		String existing = environment.getProperty(EXCLUDE_PROPERTY);
		if (existing != null && !existing.isBlank()) {
			for (String value : existing.split(",")) {
				String trimmed = value.trim();
				if (!trimmed.isEmpty()) {
					exclusions.add(trimmed);
				}
			}
		}
		for (String autoConfiguration : EXCLUDED_AUTO_CONFIGURATIONS) {
			if (!exclusions.contains(autoConfiguration)) {
				exclusions.add(autoConfiguration);
			}
		}
		return exclusions;
	}
}
