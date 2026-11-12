package com.ayush.urlshortener.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A short code mapped to its target URL, with optional expiry and a click counter. */
@Entity
@Table(name = "short_urls")
public class ShortUrl {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "short_code", nullable = false, unique = true, length = 32)
	private String shortCode;

	@Column(name = "original_url", nullable = false, length = 2048)
	private String originalUrl;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(name = "click_count", nullable = false)
	private long clickCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	protected ShortUrl() {
	}

	public ShortUrl(String shortCode, String originalUrl, Long ownerId, Instant createdAt, Instant expiresAt) {
		this.shortCode = shortCode;
		this.originalUrl = originalUrl;
		this.ownerId = ownerId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public boolean isExpired(Instant now) {
		return expiresAt != null && !now.isBefore(expiresAt);
	}

	public Long getId() {
		return id;
	}

	public String getShortCode() {
		return shortCode;
	}

	public String getOriginalUrl() {
		return originalUrl;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public long getClickCount() {
		return clickCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
