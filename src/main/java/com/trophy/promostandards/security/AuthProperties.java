package com.trophy.promostandards.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simple gate-keeping credentials for the whole app, bound from the {@code security.auth.*}
 * namespace. This is a lightweight HTTP Basic protection (see {@link BasicAuthFilter}) — one
 * username/password read from configuration — intended to keep the otherwise-unauthenticated
 * console + REST API private on the deployed server.
 *
 * <p>The password is sensitive: supply it via the {@code APP_AUTH_PASSWORD} environment variable
 * (or an untracked {@code application-local.yaml}) — never commit a real password.
 */
@ConfigurationProperties(prefix = "security.auth")
public class AuthProperties {

	/** Master switch. When false, no filter is registered and every request is allowed through. */
	private boolean enabled = false;

	/** Realm shown in the browser's Basic-auth prompt. */
	private String realm = "PromoStandards";

	/** The single accepted username. */
	private String username = "admin";

	/** The accepted password. When blank while {@link #enabled}, the filter denies every request. */
	private String password = "";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getRealm() {
		return realm;
	}

	public void setRealm(String realm) {
		this.realm = realm;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
