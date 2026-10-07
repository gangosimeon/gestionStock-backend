package com.geststock.user;

import org.junit.jupiter.api.Test;

import com.geststock.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonNameTest {

	@Test
	void prefersExplicitFirstAndLastName() {
		assertThat(PersonName.of("  Pierre ", " Diallo ", "ignoré"))
			.isEqualTo(new PersonName("Pierre", "Diallo"));
	}

	@Test
	void splitsLegacyFullNameOnFirstSpace() {
		assertThat(PersonName.of(null, null, "Marie   Koné Bamba"))
			.isEqualTo(new PersonName("Marie", "Koné Bamba"));
	}

	@Test
	void singleWordFullNameBecomesLastName() {
		assertThat(PersonName.of(null, "", "Admin")).isEqualTo(new PersonName(null, "Admin"));
	}

	@Test
	void blankFirstNameIsStoredAsNull() {
		assertThat(PersonName.of(" ", "Sow", null)).isEqualTo(new PersonName(null, "Sow"));
	}

	@Test
	void rejectsMissingName() {
		assertThatThrownBy(() -> PersonName.of(null, " ", "  ")).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsSplitPartLongerThanColumn() {
		assertThatThrownBy(() -> PersonName.of(null, null, "A " + "b".repeat(76)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("75");
	}

}
