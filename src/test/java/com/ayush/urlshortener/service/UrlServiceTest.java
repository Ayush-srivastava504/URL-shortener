package com.ayush.urlshortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.ayush.urlshortener.cache.ClickCounter;
import com.ayush.urlshortener.cache.UrlCache;
import com.ayush.urlshortener.cache.UrlCache.CachedUrl;
import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.domain.ShortUrl;
import com.ayush.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	private static final Long OWNER = 1L;

	@Mock
	private ShortUrlRepository repository;

	@Mock
	private ShortCodeGenerator generator;

	@Mock
	private UrlCache cache;

	@Mock
	private ClickCounter clickCounter;

	private UrlService service;

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties("http://localhost:8080", null, null, null);
		service = new UrlService(repository, generator, cache, clickCounter, Clock.fixed(NOW, ZoneOffset.UTC),
				properties);
	}

	@Test
	void createGeneratesRandomCode() {
		when(generator.next()).thenReturn("aB3xY9z");
		when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		ShortUrl url = service.create(OWNER, "https://example.com/page", null, null);

		assertThat(url.getShortCode()).isEqualTo("aB3xY9z");
		assertThat(url.getOriginalUrl()).isEqualTo("https://example.com/page");
		assertThat(url.getOwnerId()).isEqualTo(OWNER);
		assertThat(url.getExpiresAt()).isNull();
	}

	@Test
	void createRetriesWhenGeneratedCodeCollides() {
		when(generator.next()).thenReturn("taken01", "free002");
		when(repository.existsByShortCode("taken01")).thenReturn(true);
		when(repository.existsByShortCode("free002")).thenReturn(false);
		when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.create(OWNER, "https://example.com", null, null).getShortCode()).isEqualTo("free002");
	}

	@Test
	void createGivesUpAfterMaxAttempts() {
		when(generator.next()).thenReturn("taken01");
		when(repository.existsByShortCode("taken01")).thenReturn(true);

		assertThatThrownBy(() -> service.create(OWNER, "https://example.com", null, null))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void createUsesCustomAliasAndExpiry() {
		when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		ShortUrl url = service.create(OWNER, "https://example.com", "my-link", 7);

		assertThat(url.getShortCode()).isEqualTo("my-link");
		assertThat(url.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
		verifyNoInteractions(generator);
	}

	@Test
	void createRejectsTakenAlias() {
		when(repository.existsByShortCode("my-link")).thenReturn(true);

		assertStatus(() -> service.create(OWNER, "https://example.com", "my-link", null), HttpStatus.CONFLICT);
	}

	@Test
	void createRejectsReservedAlias() {
		assertStatus(() -> service.create(OWNER, "https://example.com", "API", null), HttpStatus.CONFLICT);
	}

	@Test
	void createMapsInsertRaceToConflict() {
		when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertStatus(() -> service.create(OWNER, "https://example.com", "my-link", null), HttpStatus.CONFLICT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://example.com", "javascript:alert(1)", "not a url", "https://",
			"http://localhost:8080/abc" })
	void createRejectsInvalidTargets(String target) {
		assertStatus(() -> service.create(OWNER, target, null, null), HttpStatus.BAD_REQUEST);
	}

	@Test
	void resolveServesFromCacheWithoutTouchingDatabase() {
		when(cache.get("abc1234")).thenReturn(Optional.of(new CachedUrl("https://example.com", null)));

		assertThat(service.resolve("abc1234")).isEqualTo("https://example.com");
		verifyNoInteractions(repository);
		verify(clickCounter).record("abc1234");
	}

	@Test
	void resolveLoadsFromDatabaseOnCacheMissAndCachesResult() {
		ShortUrl url = new ShortUrl("abc1234", "https://example.com", OWNER, NOW, null);
		when(cache.get("abc1234")).thenReturn(Optional.empty());
		when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(url));

		assertThat(service.resolve("abc1234")).isEqualTo("https://example.com");
		verify(cache).put(url, NOW);
		verify(clickCounter).record("abc1234");
	}

	@Test
	void resolveUnknownCodeIs404AndNotCounted() {
		when(cache.get("nope123")).thenReturn(Optional.empty());
		when(repository.findByShortCode("nope123")).thenReturn(Optional.empty());

		assertStatus(() -> service.resolve("nope123"), HttpStatus.NOT_FOUND);
		verify(clickCounter, never()).record(any());
	}

	@Test
	void resolveExpiredLinkIs410() {
		ShortUrl url = new ShortUrl("old1234", "https://example.com", OWNER, NOW.minusSeconds(100),
				NOW.minusSeconds(1));
		when(cache.get("old1234")).thenReturn(Optional.empty());
		when(repository.findByShortCode("old1234")).thenReturn(Optional.of(url));

		assertStatus(() -> service.resolve("old1234"), HttpStatus.GONE);
		verify(cache, never()).put(any(), any());
	}

	@Test
	void resolveExpiredCacheEntryIs410() {
		when(cache.get("old1234")).thenReturn(Optional.of(new CachedUrl("https://example.com", NOW)));

		assertStatus(() -> service.resolve("old1234"), HttpStatus.GONE);
	}

	@Test
	void otherUsersLinkLooksLikeNotFound() {
		ShortUrl url = new ShortUrl("abc1234", "https://example.com", 99L, NOW, null);
		when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(url));

		assertStatus(() -> service.getForOwner(OWNER, "abc1234"), HttpStatus.NOT_FOUND);
		assertStatus(() -> service.delete(OWNER, "abc1234"), HttpStatus.NOT_FOUND);
		verify(repository, never()).delete(any());
	}

	@Test
	void deleteRemovesRowAndEvictsCache() {
		ShortUrl url = new ShortUrl("abc1234", "https://example.com", OWNER, NOW, null);
		when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(url));

		service.delete(OWNER, "abc1234");

		verify(repository).delete(url);
		verify(cache).evict("abc1234");
	}

	private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getStatus()).isEqualTo(status));
	}

}
