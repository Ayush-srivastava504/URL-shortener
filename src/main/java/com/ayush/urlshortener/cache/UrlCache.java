package com.ayush.urlshortener.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.domain.ShortUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside store for redirect lookups: short code -> target URL (+ expiry).
 * <p>
 * Redis failures are logged and treated as a cache miss, so a Redis outage slows redirects
 * down but never breaks them.
 */
@Component
public class UrlCache {

	private static final Logger log = LoggerFactory.getLogger(UrlCache.class);

	private static final String PREFIX = "url:";

	private static final long NO_EXPIRY = -1;

	private final StringRedisTemplate redis;

	private final Duration ttl;

	public UrlCache(StringRedisTemplate redis, AppProperties properties) {
		this.redis = redis;
		this.ttl = properties.cache().ttl();
	}

	public Optional<CachedUrl> get(String code) {
		try {
			String value = redis.opsForValue().get(PREFIX + code);
			return Optional.ofNullable(value).map(UrlCache::decode);
		}
		catch (DataAccessException ex) {
			log.warn("Redis read failed for {}; falling back to database", code, ex);
			return Optional.empty();
		}
	}

	public void put(ShortUrl url, Instant now) {
		Duration entryTtl = ttl;
		if (url.getExpiresAt() != null) {
			Duration untilExpiry = Duration.between(now, url.getExpiresAt());
			if (untilExpiry.compareTo(entryTtl) < 0) {
				entryTtl = untilExpiry;
			}
		}
		if (entryTtl.isNegative() || entryTtl.isZero()) {
			return;
		}
		try {
			redis.opsForValue().set(PREFIX + url.getShortCode(), encode(url), entryTtl);
		}
		catch (DataAccessException ex) {
			log.warn("Redis write failed for {}", url.getShortCode(), ex);
		}
	}

	public void evict(String code) {
		try {
			redis.delete(PREFIX + code);
		}
		catch (DataAccessException ex) {
			log.warn("Redis evict failed for {}", code, ex);
		}
	}

	// Stored as "<expiresAtEpochMillis or -1>|<url>". The URL goes last so a '|' inside it is harmless.
	private static String encode(ShortUrl url) {
		long expires = url.getExpiresAt() == null ? NO_EXPIRY : url.getExpiresAt().toEpochMilli();
		return expires + "|" + url.getOriginalUrl();
	}

	private static CachedUrl decode(String value) {
		int sep = value.indexOf('|');
		long expires = Long.parseLong(value.substring(0, sep));
		return new CachedUrl(value.substring(sep + 1), expires == NO_EXPIRY ? null : Instant.ofEpochMilli(expires));
	}

	public record CachedUrl(String originalUrl, Instant expiresAt) {

		public boolean isExpired(Instant now) {
			return expiresAt != null && !now.isBefore(expiresAt);
		}

	}

}
