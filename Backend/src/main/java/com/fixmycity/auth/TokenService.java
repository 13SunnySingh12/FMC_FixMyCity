package com.fixmycity.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues login tokens and the httpOnly cookie that carries them. */
@Service
class TokenService {

	private final JwtEncoder encoder;

	private final Duration ttl;

	TokenService(JwtEncoder encoder, @Value("${fmc.jwt.ttl}") Duration ttl) {
		this.encoder = encoder;
		this.ttl = ttl;
	}

	ResponseCookie loginCookie(Long userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(this.ttl))
			.build();
		String token = this.encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return cookie(token, this.ttl);
	}

	ResponseCookie logoutCookie() {
		return cookie("", Duration.ZERO);
	}

	private static ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(SecurityConfig.TOKEN_COOKIE, value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Strict")
			.path("/api")
			.maxAge(maxAge)
			.build();
	}

}
