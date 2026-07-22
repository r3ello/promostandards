package com.trophy.promostandards.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Credential check plus stateless session-token minting/verification behind the form login (see
 * {@link SessionAuthFilter} and {@link AuthController}). Only created when
 * {@code security.auth.enabled=true}.
 *
 * <p>A random HMAC secret is generated once per boot, so a session token is unforgeable without the
 * running process but is invalidated on restart. For a single-admin console that trade-off is
 * deliberate — there is no shared secret to configure or leak, at the cost of everyone having to
 * sign in again after a redeploy.
 */
@Component
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);
	private static final String HMAC_ALG = "HmacSHA256";
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64D = Base64.getUrlDecoder();

	private final AuthProperties props;
	private final byte[] secret = new byte[32];

	public AuthService(AuthProperties props) {
		this.props = props;
		new SecureRandom().nextBytes(secret);
	}

	@PostConstruct
	void logStatus() {
		if (!passwordConfigured()) {
			log.warn("security.auth.enabled=true but no password is configured (set APP_AUTH_PASSWORD). "
					+ "Every login will be rejected until a password is set.");
		} else {
			log.info("Form login is ON (username='{}').", props.getUsername());
		}
	}

	public boolean passwordConfigured() {
		return props.getPassword() != null && !props.getPassword().isBlank();
	}

	/** Validates a login attempt; fails closed when no password is configured. */
	public boolean credentialsValid(String username, String password) {
		if (!passwordConfigured()) {
			return false; // fail closed: an enabled-but-unconfigured gate must let no one in
		}
		// non-short-circuit '&' so both comparisons always run (avoids leaking which field mismatched)
		return constantTimeEquals(username, props.getUsername()) & constantTimeEquals(password, props.getPassword());
	}

	/** Mints a signed session token for the configured user, valid for {@code sessionMinutes}. */
	public String issueToken() {
		long expiry = System.currentTimeMillis() + props.getSessionMinutes() * 60_000L;
		byte[] payload = (props.getUsername() + "\n" + expiry).getBytes(StandardCharsets.UTF_8);
		return B64.encodeToString(payload) + "." + B64.encodeToString(sign(payload));
	}

	/** True when {@code token} carries a valid signature and has not expired. */
	public boolean tokenValid(String token) {
		if (token == null || token.isBlank()) {
			return false;
		}
		int dot = token.indexOf('.');
		if (dot < 0) {
			return false;
		}
		try {
			byte[] payload = B64D.decode(token.substring(0, dot));
			byte[] signature = B64D.decode(token.substring(dot + 1));
			if (!MessageDigest.isEqual(sign(payload), signature)) {
				return false;
			}
			String decoded = new String(payload, StandardCharsets.UTF_8);
			int nl = decoded.indexOf('\n');
			if (nl < 0) {
				return false;
			}
			return Long.parseLong(decoded.substring(nl + 1)) > System.currentTimeMillis();
		} catch (IllegalArgumentException malformed) {
			return false;
		}
	}

	private byte[] sign(byte[] data) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALG);
			mac.init(new SecretKeySpec(secret, HMAC_ALG));
			return mac.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
