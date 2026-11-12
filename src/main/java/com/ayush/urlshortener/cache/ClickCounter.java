package com.ayush.urlshortener.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ayush.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts clicks in Redis (one HINCRBY per redirect) and writes them to PostgreSQL in batches.
 * This keeps the hot redirect path free of database writes.
 */
@Component
public class ClickCounter {

	private static final Logger log = LoggerFactory.getLogger(ClickCounter.class);

	static final String PENDING_KEY = "clicks:pending";

	private final StringRedisTemplate redis;

	private final ShortUrlRepository repository;

	private final TransactionTemplate tx;

	public ClickCounter(StringRedisTemplate redis, ShortUrlRepository repository, TransactionTemplate tx) {
		this.redis = redis;
		this.repository = repository;
		this.tx = tx;
	}

	public void record(String code) {
		try {
			redis.opsForHash().increment(PENDING_KEY, code, 1);
		}
		catch (DataAccessException ex) {
			// Redis is down: write straight to the database so the click isn't lost.
			log.warn("Redis unavailable, recording click for {} directly in the database", code, ex);
			tx.executeWithoutResult(status -> repository.incrementClicks(code, 1));
		}
	}

	/** Clicks recorded in Redis that haven't been flushed to the database yet. */
	public Map<String, Long> pending(Collection<String> codes) {
		Map<String, Long> result = new HashMap<>();
		if (codes.isEmpty()) {
			return result;
		}
		try {
			List<Object> keys = new ArrayList<>(codes);
			List<Object> values = redis.opsForHash().multiGet(PENDING_KEY, keys);
			for (int i = 0; i < keys.size(); i++) {
				Object value = values.get(i);
				if (value != null) {
					result.put((String) keys.get(i), Long.parseLong(value.toString()));
				}
			}
		}
		catch (DataAccessException ex) {
			log.warn("Could not read pending click counts", ex);
		}
		return result;
	}

	/**
	 * Atomically renames the pending hash, so new clicks go into a fresh hash while this batch is
	 * written. If several app instances run, only one wins the RENAME for a given batch.
	 */
	@Scheduled(fixedDelayString = "${app.clicks.flush-interval:PT10S}")
	public void flush() {
		String batchKey = PENDING_KEY + ":batch:" + UUID.randomUUID();
		Map<Object, Object> batch;
		try {
			if (!Boolean.TRUE.equals(redis.hasKey(PENDING_KEY))) {
				return;
			}
			redis.rename(PENDING_KEY, batchKey);
			batch = redis.opsForHash().entries(batchKey);
		}
		catch (DataAccessException ex) {
			log.debug("Skipping click flush: {}", ex.getMessage());
			return;
		}
		try {
			tx.executeWithoutResult(status -> batch.forEach(
					(code, delta) -> repository.incrementClicks((String) code, Long.parseLong(delta.toString()))));
			redis.delete(batchKey);
			log.debug("Flushed click counts for {} links", batch.size());
		}
		catch (RuntimeException ex) {
			// Keep the batch key in Redis so the counts can be recovered by hand.
			log.error("Failed to flush click batch {}", batchKey, ex);
		}
	}

}
