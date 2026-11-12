package com.ayush.urlshortener.ratelimit;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.ayush.urlshortener.config.AppProperties.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window-log rate limiter backed by a Redis sorted set (one member per request, scored
 * by timestamp). The check-and-add runs as a single Lua script, so it is atomic across app
 * instances.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>("""
			local key = KEYS[1]
			local now = tonumber(ARGV[1])
			local window = tonumber(ARGV[2])
			local limit = tonumber(ARGV[3])
			redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
			local count = redis.call('ZCARD', key)
			if count < limit then
			  redis.call('ZADD', key, now, ARGV[4])
			  redis.call('PEXPIRE', key, window)
			  return {1, limit - count - 1, 0}
			end
			local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
			return {0, 0, window - (now - tonumber(oldest[2]))}
			""", List.class);

	private final StringRedisTemplate redis;

	private final Clock clock;

	public RateLimiter(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
	}

	public Decision tryAcquire(String key, Rule rule) {
		long now = clock.millis();
		try {
			List<?> result = redis.execute(SCRIPT, List.of("rl:" + key), String.valueOf(now),
					String.valueOf(rule.window().toMillis()), String.valueOf(rule.limit()),
					now + ":" + UUID.randomUUID());
			return new Decision(toLong(result.get(0)) == 1, toLong(result.get(1)), toLong(result.get(2)));
		}
		catch (DataAccessException ex) {
			// Fail open: a Redis outage shouldn't take the whole API down.
			log.warn("Rate limiter unavailable, allowing request for {}", key, ex);
			return new Decision(true, rule.limit(), 0);
		}
	}

	private static long toLong(Object value) {
		return ((Number) value).longValue();
	}

	public record Decision(boolean allowed, long remaining, long retryAfterMillis) {
	}

}
