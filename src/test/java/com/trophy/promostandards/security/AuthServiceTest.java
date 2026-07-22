package com.trophy.promostandards.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthService}: credential checking (including the fail-closed path when no
 * password is set) and the round-trip / tamper / expiry behaviour of the session token.
 */
class AuthServiceTest {

	private static AuthProperties props(String user, String password, long sessionMinutes) {
		AuthProperties p = new AuthProperties();
		p.setEnabled(true);
		p.setUsername(user);
		p.setPassword(password);
		p.setSessionMinutes(sessionMinutes);
		return p;
	}

	@Test
	void acceptsCorrectCredentials() {
		AuthService auth = new AuthService(props("admin", "secret", 720));
		assertThat(auth.credentialsValid("admin", "secret")).isTrue();
	}

	@Test
	void rejectsWrongCredentials() {
		AuthService auth = new AuthService(props("admin", "secret", 720));
		assertThat(auth.credentialsValid("admin", "nope")).isFalse();
		assertThat(auth.credentialsValid("root", "secret")).isFalse();
	}

	@Test
	void failsClosedWhenPasswordNotConfigured() {
		AuthService auth = new AuthService(props("admin", "", 720));
		assertThat(auth.passwordConfigured()).isFalse();
		assertThat(auth.credentialsValid("admin", "")).isFalse();
	}

	@Test
	void issuedTokenVerifies() {
		AuthService auth = new AuthService(props("admin", "secret", 720));
		assertThat(auth.tokenValid(auth.issueToken())).isTrue();
	}

	@Test
	void rejectsTamperedOrGarbageToken() {
		AuthService auth = new AuthService(props("admin", "secret", 720));
		String token = auth.issueToken();
		assertThat(auth.tokenValid(token + "x")).isFalse();
		assertThat(auth.tokenValid("not-a-token")).isFalse();
		assertThat(auth.tokenValid(null)).isFalse();
		assertThat(auth.tokenValid("")).isFalse();
	}

	@Test
	void rejectsTokenFromADifferentInstance() {
		AuthProperties p = props("admin", "secret", 720);
		String token = new AuthService(p).issueToken();
		// A fresh instance has a different per-boot secret, so the signature no longer verifies.
		assertThat(new AuthService(p).tokenValid(token)).isFalse();
	}

	@Test
	void rejectsExpiredToken() {
		AuthService auth = new AuthService(props("admin", "secret", -1)); // expiry set one minute in the past
		assertThat(auth.tokenValid(auth.issueToken())).isFalse();
	}
}
