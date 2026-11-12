package com.ayush.urlshortener.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ayush.urlshortener.cache.ClickCounter;
import com.ayush.urlshortener.cache.UrlCache;
import com.ayush.urlshortener.cache.UrlCache.CachedUrl;
import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.domain.ShortUrl;
import com.ayush.urlshortener.repository.ShortUrlRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Core link logic: create, resolve (redirect), list and delete short URLs. */
@Service
public class UrlService {

	/** Aliases that would clash with the app's own routes. */
	static final Set<String> RESERVED_ALIASES = Set.of("api", "actuator", "error", "health", "login", "register");

	static final int MAX_GENERATION_ATTEMPTS = 5;

	private final ShortUrlRepository repository;

	private final ShortCodeGenerator generator;

	private final UrlCache cache;

	private final ClickCounter clickCounter;

	private final Clock clock;

	private final String ownHost;

	public UrlService(ShortUrlRepository repository, ShortCodeGenerator generator, UrlCache cache,
			ClickCounter clickCounter, Clock clock, AppProperties properties) {
		this.repository = repository;
		this.generator = generator;
		this.cache = cache;
		this.clickCounter = clickCounter;
		this.clock = clock;
		this.ownHost = URI.create(properties.baseUrl()).getHost();
	}

	@Transactional
	public ShortUrl create(Long ownerId, String url, String alias, Integer expiresInDays) {
		String target = validateTarget(url);
		Instant now = clock.instant();
		Instant expiresAt = (expiresInDays == null) ? null : now.plus(Duration.ofDays(expiresInDays));

		String code;
		if (alias != null && !alias.isBlank()) {
			if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
				throw ApiException.conflict("Alias '" + alias + "' is reserved");
			}
			if (repository.existsByShortCode(alias)) {
				throw ApiException.conflict("Alias '" + alias + "' is already taken");
			}
			code = alias;
		}
		else {
			code = generateUniqueCode();
		}

		try {
			return repository.saveAndFlush(new ShortUrl(code, target, ownerId, now, expiresAt));
		}
		catch (DataIntegrityViolationException ex) {
			// Another request claimed the same code between our check and the insert.
			throw ApiException.conflict("Short code '" + code + "' is already taken");
		}
	}

	/**
	 * Hot path: resolves a code to its target URL. It checks Redis first and only goes to
	 * PostgreSQL on a cache miss. The click is counted in Redis, not written to the database.
	 */
	public String resolve(String code) {
		Instant now = clock.instant();
		Optional<CachedUrl> cached = cache.get(code);
		String target;
		if (cached.isPresent()) {
			if (cached.get().isExpired(now)) {
				throw ApiException.gone("This link has expired");
			}
			target = cached.get().originalUrl();
		}
		else {
			ShortUrl url = repository.findByShortCode(code)
				.orElseThrow(() -> ApiException.notFound("Short link not found"));
			if (url.isExpired(now)) {
				throw ApiException.gone("This link has expired");
			}
			cache.put(url, now);
			target = url.getOriginalUrl();
		}
		clickCounter.record(code);
		return target;
	}

	@Transactional(readOnly = true)
	public Page<ShortUrl> listForOwner(Long ownerId, int page, int size) {
		return repository.findByOwnerIdOrderByCreatedAtDesc(ownerId, PageRequest.of(page, size));
	}

	@Transactional(readOnly = true)
	public ShortUrl getForOwner(Long ownerId, String code) {
		ShortUrl url = repository.findByShortCode(code)
			.orElseThrow(() -> ApiException.notFound("Short link not found"));
		// Answer 404 (not 403) for other users' links, so link ownership isn't revealed.
		if (!url.getOwnerId().equals(ownerId)) {
			throw ApiException.notFound("Short link not found");
		}
		return url;
	}

	@Transactional
	public void delete(Long ownerId, String code) {
		ShortUrl url = getForOwner(ownerId, code);
		repository.delete(url);
		// Evict only after commit; evicting earlier lets a concurrent redirect re-cache the row.
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					cache.evict(code);
				}
			});
		}
		else {
			cache.evict(code);
		}
	}

	/** Total clicks = clicks already flushed to the database + clicks still pending in Redis. */
	public Map<String, Long> pendingClicks(List<ShortUrl> urls) {
		return clickCounter.pending(urls.stream().map(ShortUrl::getShortCode).toList());
	}

	private String generateUniqueCode() {
		for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
			String candidate = generator.next();
			if (!repository.existsByShortCode(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not generate a unique short code");
	}

	private String validateTarget(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		}
		catch (URISyntaxException ex) {
			throw ApiException.badRequest("Not a valid URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw ApiException.badRequest("Only http and https URLs can be shortened");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw ApiException.badRequest("URL must include a host");
		}
		if (uri.getHost().equalsIgnoreCase(ownHost)) {
			throw ApiException.badRequest("Cannot shorten a link that points to this service");
		}
		return uri.toString();
	}

}
