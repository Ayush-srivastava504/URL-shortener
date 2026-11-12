package com.ayush.urlshortener.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.domain.AppUser;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues HS256-signed JWTs whose subject is the user id. */
@Service
public class TokenService {

	static final String ISSUER = "url-shortener";

	private final JwtEncoder encoder;

	private final Clock clock;

	private final Duration ttl;

	public TokenService(JwtEncoder encoder, Clock clock, AppProperties properties) {
		this.encoder = encoder;
		this.clock = clock;
		this.ttl = properties.jwt().ttl();
	}

	public IssuedToken issue(AppUser user) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.subject(String.valueOf(user.getId()))
			.claim("username", user.getUsername())
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, ttl.toSeconds());
	}

	public record IssuedToken(String value, long expiresInSeconds) {
	}

}
