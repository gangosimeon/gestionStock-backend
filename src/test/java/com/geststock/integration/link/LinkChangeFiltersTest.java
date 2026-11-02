package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.geststock.integration.ChangeFilter;
import com.geststock.integration.IntegrationEventType;

/**
 * Filtre du flux selon les portées (revue G16) : seule la disponibilité dépend de {@code stock:read} ; tout autre type,
 * connu ou non, est servi quelle que soit la portée.
 */
class LinkChangeFiltersTest {

	private static final UUID OBJECT = UUID.randomUUID();

	@Test
	void withoutStockReadOnlyTheAvailabilityIsSetAside() {
		ChangeFilter filter = LinkChangeFilters.of(principal("catalog:read"));

		for (IntegrationEventType type : IntegrationEventType.values()) {
			assertThat(filter.accepts(type, OBJECT)).as(type.name()).isEqualTo(type != IntegrationEventType.AVAILABILITY);
		}
		assertThat(filter.acceptsUnknown("FUTURE_TYPE", OBJECT)).isTrue();
	}

	@Test
	void withStockReadEveryTypeIsServed() {
		ChangeFilter filter = LinkChangeFilters.of(principal("catalog:read", "stock:read"));

		for (IntegrationEventType type : IntegrationEventType.values()) {
			assertThat(filter.accepts(type, OBJECT)).as(type.name()).isTrue();
		}
		assertThat(filter.acceptsUnknown("FUTURE_TYPE", OBJECT)).isTrue();
	}

	@Test
	void ordersWriteDoesNotImplyStockRead() {
		ChangeFilter filter = LinkChangeFilters.of(principal("catalog:read", "orders:write", "orders:read"));

		assertThat(filter.accepts(IntegrationEventType.AVAILABILITY, OBJECT)).isFalse();
	}

	private static LinkPrincipal principal(String... scopes) {
		return new LinkPrincipal(UUID.randomUUID(), "jcmb-test", Set.of(scopes));
	}

}
