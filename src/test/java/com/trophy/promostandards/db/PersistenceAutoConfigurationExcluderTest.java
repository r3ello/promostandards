package com.trophy.promostandards.db;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static com.trophy.promostandards.db.PersistenceAutoConfigurationExcluder.ENABLED_PROPERTY;
import static com.trophy.promostandards.db.PersistenceAutoConfigurationExcluder.EXCLUDE_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Postgres driver is on the classpath unconditionally, so without this post-processor Spring
 * Boot auto-configures a DataSource and fails startup ("Failed to configure a DataSource") on any
 * install that has no database — which is every install until the flag is turned on.
 */
class PersistenceAutoConfigurationExcluderTest {

	private final PersistenceAutoConfigurationExcluder excluder = new PersistenceAutoConfigurationExcluder();

	@Test
	void excludesTheDatabaseAutoConfigurationWhenPersistenceIsOff() {
		MockEnvironment environment = new MockEnvironment();

		excluder.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty(EXCLUDE_PROPERTY))
				.contains("DataSourceAutoConfiguration")
				.contains("FlywayAutoConfiguration");
	}

	@Test
	void leavesAutoConfigurationAloneWhenPersistenceIsOn() {
		MockEnvironment environment = new MockEnvironment().withProperty(ENABLED_PROPERTY, "true");

		excluder.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty(EXCLUDE_PROPERTY)).isNull();
	}

	/** Someone else's exclusions must survive: this appends, it does not take the property over. */
	@Test
	void appendsToExclusionsTheApplicationAlreadyDeclares() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(EXCLUDE_PROPERTY, "com.example.SomeOtherAutoConfiguration");

		excluder.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty(EXCLUDE_PROPERTY))
				.contains("com.example.SomeOtherAutoConfiguration")
				.contains("DataSourceAutoConfiguration");
	}

	@Test
	void doesNotRepeatAnExclusionThatIsAlreadyListed() {
		MockEnvironment environment = new MockEnvironment().withProperty(EXCLUDE_PROPERTY,
				"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");

		excluder.postProcessEnvironment(environment, null);

		String exclusions = environment.getProperty(EXCLUDE_PROPERTY);
		assertThat(exclusions.split("DataSourceAutoConfiguration", -1)).hasSize(2);  // exactly one occurrence
	}
}
