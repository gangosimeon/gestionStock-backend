package com.geststock.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Différences avant/après et masquage des champs sensibles. */
class AuditDiffTest {

	private final AuditDiff diff = new AuditDiff(JsonMapper.builder().findAndAddModules().build());

	record ProductState(String sku, BigDecimal retailPrice, int alertThreshold, Long version, Instant updatedAt) {
	}

	@Test
	void onlyRealChangesAreListedInTheOrderOfTheFinalState() {
		JsonNode before = this.diff.state(new ProductState("MON-1", new BigDecimal("32000"), 3, 4L,
				Instant.parse("2026-10-01T10:00:00Z")));
		JsonNode after = this.diff.state(new ProductState("MON-1", new BigDecimal("35000.00"), 3, 5L,
				Instant.parse("2026-10-10T10:00:00Z")));

		JsonNode changes = this.diff.changes(before, after);

		assertThat(changes).hasSize(1);
		assertThat(changes.get(0).get("field").asString()).isEqualTo("retailPrice");
		assertThat(changes.get(0).get("oldValue").decimalValue()).isEqualByComparingTo("32000");
		assertThat(changes.get(0).get("newValue").decimalValue()).isEqualByComparingTo("35000");
	}

	@Test
	void numbersAreComparedByValueAndNullsAreChanges() {
		JsonNode changes = this.diff.changes(this.diff.state(Map.of("price", new BigDecimal("1000.00"), "note", "x")),
				this.diff.state(Map.of("price", new BigDecimal("1000"))));

		assertThat(changes).hasSize(1);
		assertThat(changes.get(0).get("field").asString()).isEqualTo("note");
		assertThat(changes.get(0).get("newValue").isNull()).isTrue();
		assertThat(this.diff.changes(null, this.diff.state(Map.of("a", 1)))).as("création : pas de diff").isNull();
	}

	@Test
	void sensitiveFieldsAreMaskedAtEveryLevel() {
		JsonNode state = this.diff.state(Map.of("username", "marie.k", "passwordHash", "$2a$10$abc",
				"session", Map.of("refreshToken", "secret-value", "device", "pc"),
				"keys", List.of(Map.of("apiSecret", "s3cr3t"))));

		assertThat(state.get("username").asString()).isEqualTo("marie.k");
		assertThat(state.get("passwordHash").asString()).isEqualTo(AuditDiff.MASK);
		assertThat(state.get("session").get("refreshToken").asString()).isEqualTo(AuditDiff.MASK);
		assertThat(state.get("session").get("device").asString()).isEqualTo("pc");
		assertThat(state.get("keys").get(0).get("apiSecret").asString()).isEqualTo(AuditDiff.MASK);
		assertThat(state.toString()).doesNotContain("$2a$10$abc", "secret-value", "s3cr3t");
	}

	@Test
	void unknownEntityTypesAreRejectedAtDevelopmentTime() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> AuditEvent.of("CREATE", "UNKNOWN_THING", null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(AuditModule.of("PRODUCT")).isEqualTo(AuditModule.CATALOG);
		assertThat(AuditModule.of("ROLE")).isEqualTo(AuditModule.SECURITY);
	}

	@Test
	void csvCellsAreQuotedAndFormulasNeutralised() {
		assertThat(AuditLogQueryService.cell("simple")).isEqualTo("simple");
		assertThat(AuditLogQueryService.cell("a;b")).isEqualTo("\"a;b\"");
		assertThat(AuditLogQueryService.cell("dit \"oui\"")).isEqualTo("\"dit \"\"oui\"\"\"");
		assertThat(AuditLogQueryService.cell("=SOMME(A1)")).isEqualTo("'=SOMME(A1)");
		assertThat(AuditLogQueryService.cell("ligne1\nligne2")).isEqualTo("ligne1 ligne2");
		assertThat(AuditLogQueryService.cell(null)).isEmpty();
	}

}
