package com.ayush.urlshortener.service;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

	@Test
	void encodesKnownValues() {
		assertThat(Base62.encode(0)).isEqualTo("0");
		assertThat(Base62.encode(61)).isEqualTo("z");
		assertThat(Base62.encode(62)).isEqualTo("10");
		assertThat(Base62.encode(3843)).isEqualTo("zz");
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, 1, 61, 62, 12345, 56_800_235_584L, Long.MAX_VALUE })
	void decodeIsInverseOfEncode(long value) {
		assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
	}

	@Test
	void rejectsNegativeValues() {
		assertThatIllegalArgumentException().isThrownBy(() -> Base62.encode(-1));
	}

	@Test
	void rejectsInvalidCharacters() {
		assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode("ab-c"));
		assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode(""));
	}

	@Test
	void detectsOverflow() {
		assertThatThrownBy(() -> Base62.decode("zzzzzzzzzzzzzzzz")).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void randomProducesRequestedLengthFromAlphabet() {
		String code = Base62.random(7, new Random(42));
		assertThat(code).hasSize(7).matches("[0-9A-Za-z]{7}");
	}

}
