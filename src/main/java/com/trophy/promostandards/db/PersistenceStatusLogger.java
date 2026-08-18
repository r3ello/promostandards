package com.trophy.promostandards.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * States at startup whether the service is running with or without its database, and — when with —
 * proves the connection actually works.
 *
 * <p>Worth a log line because the flag is set through the environment on the server: the difference
 * between "persistence enabled" and "the variable did not reach the container" is otherwise invisible
 * until something silently behaves like the old build.
 */
@Component
public class PersistenceStatusLogger {

	private static final Logger log = LoggerFactory.getLogger(PersistenceStatusLogger.class);

	private final PersistenceProperties props;
	private final ObjectProvider<DataSource> dataSource;

	public PersistenceStatusLogger(PersistenceProperties props, ObjectProvider<DataSource> dataSource) {
		this.props = props;
		this.dataSource = dataSource;
	}

	@EventListener(ApplicationReadyEvent.class)
	void report() {
		if (!props.enabled()) {
			log.info("Persistence DISABLED (sync.persistence.enabled=false): catalog indexes stay in memory "
					+ "and in data/*.json, and scheduled syncs push every imported product");
			return;
		}
		DataSource source = dataSource.getIfAvailable();
		if (source == null) {
			// Enabled but nothing was auto-configured: the exclusion ran on a stale flag, or the
			// datasource failed to build. Loud, because the sync path assumes its bookkeeping exists.
			log.error("Persistence ENABLED but no DataSource was configured — check DB_URL/DB_USER/DB_PASSWORD");
			return;
		}
		try (Connection connection = source.getConnection()) {
			log.info("Persistence ENABLED: connected to {} ({})",
					connection.getMetaData().getURL(),
					connection.getMetaData().getDatabaseProductVersion());
		} catch (SQLException e) {
			log.error("Persistence ENABLED but the database is unreachable: {}", e.getMessage());
		}
	}
}
