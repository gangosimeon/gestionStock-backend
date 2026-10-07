package com.geststock.common.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Sort;

import com.geststock.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortWhitelistTest {

	private final SortWhitelist whitelist = new SortWhitelist(Map.of("name", "lastName", "createdAt", "createdAt"),
			Sort.by("lastName"));

	@Test
	void appliesDefaultSortWithStableIdTiebreaker() {
		assertThat(this.whitelist.resolve(null)).isEqualTo(Sort.by("lastName").and(Sort.by("id")));
	}

	@Test
	void handlesValuesSplitBySpringOnCommas() {
		// sort=name,desc&sort=createdAt arrive en ["name", "desc", "createdAt"]
		Sort sort = this.whitelist.resolve(List.of("name", "desc", "createdAt"));

		assertThat(sort).containsExactly(Sort.Order.desc("lastName"), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
	}

	@Test
	void handlesUnsplitValues() {
		assertThat(this.whitelist.resolve(List.of("name,desc"))).first().isEqualTo(Sort.Order.desc("lastName"));
	}

	@Test
	void rejectsFieldOutsideWhitelist() {
		assertThatThrownBy(() -> this.whitelist.resolve(List.of("passwordHash")))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("passwordHash");
	}

	@Test
	void rejectsDirectionWithoutField() {
		assertThatThrownBy(() -> this.whitelist.resolve(List.of("desc"))).isInstanceOf(InvalidRequestException.class);
	}

}
