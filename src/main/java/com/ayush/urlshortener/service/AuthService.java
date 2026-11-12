package com.ayush.urlshortener.service;

import java.time.Clock;

import com.ayush.urlshortener.domain.AppUser;
import com.ayush.urlshortener.repository.AppUserRepository;
import com.ayush.urlshortener.security.TokenService;
import com.ayush.urlshortener.security.TokenService.IssuedToken;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Handles user registration and login, returning a signed JWT on success. */
@Service
public class AuthService {

	private final AppUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokens;

	private final Clock clock;

	public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, TokenService tokens, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.clock = clock;
	}

	@Transactional
	public IssuedToken register(String username, String password) {
		if (users.existsByUsername(username)) {
			throw ApiException.conflict("Username is already taken");
		}
		try {
			AppUser user = users.saveAndFlush(new AppUser(username, passwordEncoder.encode(password), clock.instant()));
			return tokens.issue(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("Username is already taken");
		}
	}

	@Transactional(readOnly = true)
	public IssuedToken login(String username, String password) {
		AppUser user = users.findByUsername(username)
			.filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
			// Same message for "unknown user" and "wrong password", so usernames can't be probed.
			.orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
		return tokens.issue(user);
	}

}
