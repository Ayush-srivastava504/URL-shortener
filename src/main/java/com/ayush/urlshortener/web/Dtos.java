package com.ayush.urlshortener.web;

import java.time.Instant;
import java.util.List;

import com.ayush.urlshortener.domain.ShortUrl;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request and response bodies for the REST API. */
public final class Dtos {

	private Dtos() {
	}

	public record CredentialsRequest(
			@NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$",
					message = "must be 3-50 letters, digits or underscores") String username,
			// BCrypt only uses the first 72 bytes of a password.
			@NotBlank @Size(min = 8, max = 72) String password) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	public record CreateUrlRequest(
			@NotBlank @Size(max = 2048) String url,
			@Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$",
					message = "must be 4-32 letters, digits, '-' or '_'") String alias,
			@Positive @Max(3650) Integer expiresInDays) {
	}

	public record UrlResponse(String shortCode, String shortUrl, String originalUrl, Instant createdAt,
			Instant expiresAt, long clicks) {

		static UrlResponse from(ShortUrl url, String baseUrl, long pendingClicks) {
			return new UrlResponse(url.getShortCode(), baseUrl + "/" + url.getShortCode(), url.getOriginalUrl(),
					url.getCreatedAt(), url.getExpiresAt(), url.getClickCount() + pendingClicks);
		}

	}

	public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
	}

}
