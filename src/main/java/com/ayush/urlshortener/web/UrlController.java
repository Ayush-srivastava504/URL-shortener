package com.ayush.urlshortener.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.domain.ShortUrl;
import com.ayush.urlshortener.service.UrlService;
import com.ayush.urlshortener.web.Dtos.CreateUrlRequest;
import com.ayush.urlshortener.web.Dtos.PageResponse;
import com.ayush.urlshortener.web.Dtos.UrlResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated CRUD API for the current user's short links. */
@RestController
@Validated
@RequestMapping("/api/urls")
public class UrlController {

	private final UrlService urlService;

	private final String baseUrl;

	public UrlController(UrlService urlService, AppProperties properties) {
		this.urlService = urlService;
		this.baseUrl = properties.baseUrl();
	}

	@PostMapping
	public ResponseEntity<UrlResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateUrlRequest request) {
		ShortUrl url = urlService.create(userId(jwt), request.url(), request.alias(), request.expiresInDays());
		return ResponseEntity.created(URI.create("/api/urls/" + url.getShortCode()))
			.body(UrlResponse.from(url, baseUrl, 0));
	}

	@GetMapping
	public PageResponse<UrlResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		Page<ShortUrl> result = urlService.listForOwner(userId(jwt), page, size);
		Map<String, Long> pending = urlService.pendingClicks(result.getContent());
		List<UrlResponse> items = result.getContent()
			.stream()
			.map(u -> UrlResponse.from(u, baseUrl, pending.getOrDefault(u.getShortCode(), 0L)))
			.toList();
		return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(),
				result.getTotalPages());
	}

	@GetMapping("/{code}")
	public UrlResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
		ShortUrl url = urlService.getForOwner(userId(jwt), code);
		long pending = urlService.pendingClicks(List.of(url)).getOrDefault(code, 0L);
		return UrlResponse.from(url, baseUrl, pending);
	}

	@DeleteMapping("/{code}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
		urlService.delete(userId(jwt), code);
		return ResponseEntity.noContent().build();
	}

	private static Long userId(Jwt jwt) {
		return Long.valueOf(jwt.getSubject());
	}

}
