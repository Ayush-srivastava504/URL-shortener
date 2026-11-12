package com.ayush.urlshortener.service;

import java.util.random.RandomGenerator;

/**
 * Base62 (0-9, A-Z, a-z) encoding. URL-safe, case-sensitive, and dense: 7 characters give
 * 62^7 (about 3.5 trillion) distinct codes.
 */
public final class Base62 {

	static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	private static final int BASE = ALPHABET.length();

	private Base62() {
	}

	public static String encode(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("value must be non-negative");
		}
		if (value == 0) {
			return "0";
		}
		StringBuilder sb = new StringBuilder();
		while (value > 0) {
			sb.append(ALPHABET.charAt((int) (value % BASE)));
			value /= BASE;
		}
		return sb.reverse().toString();
	}

	public static long decode(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			throw new IllegalArgumentException("encoded value must not be empty");
		}
		long result = 0;
		for (int i = 0; i < encoded.length(); i++) {
			int digit = ALPHABET.indexOf(encoded.charAt(i));
			if (digit < 0) {
				throw new IllegalArgumentException("invalid Base62 character: " + encoded.charAt(i));
			}
			result = Math.addExact(Math.multiplyExact(result, BASE), digit);
		}
		return result;
	}

	public static String random(int length, RandomGenerator random) {
		if (length <= 0) {
			throw new IllegalArgumentException("length must be positive");
		}
		char[] chars = new char[length];
		for (int i = 0; i < length; i++) {
			chars[i] = ALPHABET.charAt(random.nextInt(BASE));
		}
		return new String(chars);
	}

}
