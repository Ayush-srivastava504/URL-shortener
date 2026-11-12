package com.ayush.urlshortener.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed binding for the {@code app.*} settings in application.properties. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl, Jwt jwt, Cache cache, RateLimit rateLimit) {

	public record Jwt(String secret, Duration ttl) {
	}

	public record Cache(Duration ttl) {
	}

	public record RateLimit(Rule create, Rule auth) {
	}

	public record Rule(int limit, Duration window) {
	}

}
