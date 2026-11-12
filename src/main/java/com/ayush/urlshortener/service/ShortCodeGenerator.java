package com.ayush.urlshortener.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Generates random 7-character Base62 codes. Random codes (rather than encoding the database
 * id) mean links can't be enumerated by counting upwards.
 */
@Component
public class ShortCodeGenerator {

	static final int LENGTH = 7;

	private final SecureRandom random = new SecureRandom();

	public String next() {
		return Base62.random(LENGTH, random);
	}

}
