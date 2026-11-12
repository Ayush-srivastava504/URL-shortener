package com.ayush.urlshortener.web;

import com.ayush.urlshortener.security.TokenService.IssuedToken;
import com.ayush.urlshortener.service.AuthService;
import com.ayush.urlshortener.web.Dtos.CredentialsRequest;
import com.ayush.urlshortener.web.Dtos.TokenResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints for registering and logging in. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse register(@Valid @RequestBody CredentialsRequest request) {
		return toResponse(authService.register(request.username(), request.password()));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody CredentialsRequest request) {
		return toResponse(authService.login(request.username(), request.password()));
	}

	private static TokenResponse toResponse(IssuedToken token) {
		return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
	}

}
