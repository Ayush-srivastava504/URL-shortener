package com.ayush.urlshortener.web;

import java.net.URI;

import com.ayush.urlshortener.service.UrlService;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public redirect endpoint: {@code GET /{code}}. */
@RestController
public class RedirectController {

	private final UrlService urlService;

	public RedirectController(UrlService urlService) {
		this.urlService = urlService;
	}

	/**
	 * Uses 302 (not 301) with no-store, so browsers don't cache the redirect and every click
	 * reaches the server and gets counted.
	 */
	@GetMapping("/{code:[A-Za-z0-9_-]{1,32}}")
	public ResponseEntity<Void> redirect(@PathVariable String code) {
		String target = urlService.resolve(code);
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(target))
			.cacheControl(CacheControl.noStore())
			.build();
	}

}
