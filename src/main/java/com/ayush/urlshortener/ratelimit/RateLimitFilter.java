package com.ayush.urlshortener.ratelimit;

import java.io.IOException;

import com.ayush.urlshortener.config.AppProperties;
import com.ayush.urlshortener.config.AppProperties.Rule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies rate limits to write endpoints. Link creation is limited per user and login/register
 * per client IP (to slow down credential stuffing). Registered inside the Spring Security chain,
 * after JWT authentication, so the user id is already known.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimiter limiter;

	private final AppProperties.RateLimit rules;

	public RateLimitFilter(RateLimiter limiter, AppProperties.RateLimit rules) {
		this.limiter = limiter;
		this.rules = rules;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!"POST".equals(request.getMethod())) {
			chain.doFilter(request, response);
			return;
		}
		String path = request.getRequestURI();
		String key;
		Rule rule;
		if (path.equals("/api/urls")) {
			key = "create:" + clientKey(request);
			rule = rules.create();
		}
		else if (path.startsWith("/api/auth/")) {
			key = "auth:ip:" + request.getRemoteAddr();
			rule = rules.auth();
		}
		else {
			chain.doFilter(request, response);
			return;
		}

		RateLimiter.Decision decision = limiter.tryAcquire(key, rule);
		response.setHeader("X-RateLimit-Limit", String.valueOf(rule.limit()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter()
			.write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
					+ "\"detail\":\"Rate limit exceeded. Retry in " + retryAfterSeconds + " seconds.\"}");
	}

	private static String clientKey(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth instanceof JwtAuthenticationToken jwt) {
			return "user:" + jwt.getToken().getSubject();
		}
		return "ip:" + request.getRemoteAddr();
	}

}
