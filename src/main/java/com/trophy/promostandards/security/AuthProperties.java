package com.trophy.promostandards.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simple gate-keeping credentials for the whole app, bound from the {@code security.auth.*}
 * namespace. This backs a lightweight form login (see {@link AuthController} /
 * {@link SessionAuthFilter}) — one username/password read from configuration — intended to keep
 * the otherwise-unauthenticated console + REST API private on the deployed server.
 *
 * <p>The password is sensitive: supply it via the {@code APP_AUTH_PASSWORD} environment variable
 * (or an untracked {@code application-local.yaml}) — never commit a real password.
 */
@ConfigurationProperties(prefix = "security.auth")
public class AuthProperties {

	/** Master switch. When false, no filter is registered and every request is allowed through. */
	private boolean enabled = false;

	/** Label kept for backwards compatibility with existing config; no longer surfaced in the UI. */
	private String realm = "PromoStandards";

	/** The single accepted username. */
	private String username = "admin";

	/** The accepted password. When blank while {@link #enabled}, every login is rejected. */
	private String password = "";

	/** Name of the HttpOnly session cookie set on a successful login. */
	private String cookieName = "PS_SESSION";

	/** How long an issued session stays valid, in minutes (default 12h). */
	private long sessionMinutes = 720;

	/**
	 * Whether the session cookie carries the {@code Secure} flag. Leave false for plain-HTTP local
	 * runs; the prod profile (served over HTTPS behind nginx) should set it true.
	 */
	private boolean cookieSecure = false;

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

	public String getCookieName() {
		return cookieName;
	}

	public void setCookieName(String cookieName) {
		this.cookieName = cookieName;
	}

	public long getSessionMinutes() {
		return sessionMinutes;
	}

	public void setSessionMinutes(long sessionMinutes) {
		this.sessionMinutes = sessionMinutes;
	}

	public boolean isCookieSecure() {
		return cookieSecure;
	}

	public void setCookieSecure(boolean cookieSecure) {
		this.cookieSecure = cookieSecure;
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
