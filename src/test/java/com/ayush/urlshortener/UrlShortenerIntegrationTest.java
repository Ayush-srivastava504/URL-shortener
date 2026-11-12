package com.ayush.urlshortener;

import java.util.UUID;

import com.ayush.urlshortener.cache.ClickCounter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests against real PostgreSQL and Redis containers. They are skipped when Docker
 * isn't available and always run in CI.
 */
@SpringBootTest(properties = { "app.rate-limit.create.limit=5", "app.rate-limit.auth.limit=1000",
		"app.clicks.flush-interval=PT1H" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerIntegrationTest {

	private static final String TARGET = "https://example.com/some/path?q=1";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ClickCounter clickCounter;

	@Test
	void createRedirectAndCountClicks() throws Exception {
		String token = registerUser();
		String code = createLink(token, "{\"url\":\"" + TARGET + "\"}");

		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/" + code))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, TARGET))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		}

		// Clicks still pending in Redis are already included in the count...
		mvc.perform(get("/api/urls/" + code).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.clicks").value(3));

		// ...and the total stays the same after they are flushed to PostgreSQL.
		clickCounter.flush();
		mvc.perform(get("/api/urls/" + code).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.clicks").value(3));
	}

	@Test
	void customAliasMustBeUnique() throws Exception {
		String token = registerUser();
		String alias = "a" + UUID.randomUUID().toString().substring(0, 8);
		createLink(token, "{\"url\":\"" + TARGET + "\",\"alias\":\"" + alias + "\"}");

		mvc.perform(post("/api/urls").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + TARGET + "\",\"alias\":\"" + alias + "\"}")).andExpect(status().isConflict());
	}

	@Test
	void rejectsInvalidInput() throws Exception {
		String token = registerUser();
		mvc.perform(post("/api/urls").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"ftp://example.com\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/urls").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + TARGET + "\",\"alias\":\"ab\"}")).andExpect(status().isBadRequest());
	}

	@Test
	void apiRequiresAuthentication() throws Exception {
		mvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + TARGET + "\"}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/urls").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithWrongPasswordFails() throws Exception {
		String username = uniqueUsername();
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content(credentials(username, "correct-password"))).andExpect(status().isCreated());

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(credentials(username, "correct-password"))).andExpect(status().isOk());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(credentials(username, "wrong-password"))).andExpect(status().isUnauthorized());
	}

	@Test
	void unknownCodeIs404() throws Exception {
		mvc.perform(get("/doesNotExist99")).andExpect(status().isNotFound());
	}

	@Test
	void usersCannotSeeOrDeleteEachOthersLinks() throws Exception {
		String owner = registerUser();
		String stranger = registerUser();
		String code = createLink(owner, "{\"url\":\"" + TARGET + "\"}");

		mvc.perform(get("/api/urls/" + code).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/urls/" + code).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
			.andExpect(status().isNotFound());
		mvc.perform(get("/" + code)).andExpect(status().isFound());
	}

	@Test
	void deletedLinkStopsRedirectingEvenWhenCached() throws Exception {
		String token = registerUser();
		String code = createLink(token, "{\"url\":\"" + TARGET + "\"}");
		mvc.perform(get("/" + code)).andExpect(status().isFound()); // now cached in Redis

		mvc.perform(delete("/api/urls/" + code).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		mvc.perform(get("/" + code)).andExpect(status().isNotFound());
	}

	@Test
	void listIsPaginatedNewestFirst() throws Exception {
		String token = registerUser();
		createLink(token, "{\"url\":\"https://example.com/1\"}");
		createLink(token, "{\"url\":\"https://example.com/2\"}");
		createLink(token, "{\"url\":\"https://example.com/3\"}");

		mvc.perform(get("/api/urls?page=0&size=2").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].originalUrl").value("https://example.com/3"))
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void linkCreationIsRateLimitedPerUser() throws Exception {
		String token = registerUser();
		for (int i = 0; i < 5; i++) {
			createLink(token, "{\"url\":\"https://example.com/" + i + "\"}");
		}
		mvc.perform(post("/api/urls").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + TARGET + "\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.detail", containsString("Rate limit exceeded")));

		// The limit is per user, so another user isn't affected.
		createLink(registerUser(), "{\"url\":\"" + TARGET + "\"}");
	}

	private String registerUser() throws Exception {
		String body = mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(credentials(uniqueUsername(), "s3cure-password")))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String createLink(String token, String json) throws Exception {
		String body = mvc
			.perform(post("/api/urls").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.shortCode");
	}

	private static String uniqueUsername() {
		return "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String credentials(String username, String password) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

}
