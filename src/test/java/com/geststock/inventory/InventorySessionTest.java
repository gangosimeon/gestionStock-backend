package com.geststock.inventory;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Workflow de l'inventaire, sans base ni Spring. */
class InventorySessionTest {

	private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

	private static final UUID USER = UUID.randomUUID();

	private final UUID frame = UUID.randomUUID();

	private final UUID lens = UUID.randomUUID();

	@Test
	void theFullWorkflowRecordsDifferences() {
		InventorySession inventory = draft();
		assertThat(inventory.getLines()).extracting(InventoryLine::getLineNumber).containsExactly(1, 2);

		inventory.start(T0, USER);
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.IN_PROGRESS);

		inventory.count(line(inventory, this.frame), 8, 10, T0, USER, "2 cassées");
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.COUNTING);
		inventory.count(line(inventory, this.lens), 5, 4, T0, USER, null);
		// Recomptage avant soumission : remplace le précédent.
		inventory.count(line(inventory, this.frame), 9, 10, T0.plusSeconds(60), USER, "1 cassée");

		assertThat(line(inventory, this.frame).getDifference()).isEqualTo(-1);
		assertThat(line(inventory, this.lens).getDifference()).isEqualTo(1);
		assertThat(inventory.totalDifference()).isZero();
		assertThat(inventory.countedCount()).isEqualTo(2);

		inventory.submit(T0, USER);
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.VALIDATION);
		inventory.complete(T0, USER, "RAS");
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.COMPLETED);
		assertThat(inventory.getValidatedBy()).isEqualTo(USER);
	}

	@Test
	void everyProductMustBeCountedBeforeSubmission() {
		InventorySession inventory = draft();
		inventory.start(T0, USER);
		inventory.count(line(inventory, this.frame), 3, 3, T0, USER, null);

		assertThatThrownBy(() -> inventory.submit(T0, USER))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo(ErrorCode.INVENTORY_LINES_NOT_COUNTED);
				assertThat(ex.getDetails()).containsEntry("uncounted", 1L);
			});
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.COUNTING);
	}

	@Test
	void theScopeIsOnlyEditableAsADraft() {
		InventorySession inventory = draft();
		inventory.addProduct(this.frame, "MON-1", "Monture");
		assertThat(inventory.getLines()).as("pas de doublon").hasSize(2);
		inventory.removeProduct(this.lens);
		inventory.addProduct(UUID.randomUUID(), "SOL-1", "Solution");
		assertThat(inventory.getLines()).extracting(InventoryLine::getLineNumber).containsExactly(1, 2);

		inventory.start(T0, USER);
		assertThatThrownBy(() -> inventory.addProduct(UUID.randomUUID(), "X", "X")).satisfies(this::invalidTransition);
		assertThatThrownBy(() -> inventory.removeProduct(this.frame)).satisfies(this::invalidTransition);
	}

	@Test
	void theValidatorCanSendItBackToCounting() {
		InventorySession inventory = counted();
		inventory.submit(T0, USER);
		inventory.reopen();

		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.COUNTING);
		assertThat(inventory.getSubmittedAt()).isNull();
		inventory.count(line(inventory, this.frame), 0, 10, T0, USER, "Recompté");
		inventory.submit(T0, USER);
		assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.VALIDATION);
	}

	@Test
	void stepsCannotBeSkipped() {
		InventorySession inventory = draft();
		assertThatThrownBy(() -> inventory.count(line(inventory, this.frame), 1, 1, T0, USER, null))
			.satisfies(this::invalidTransition);
		assertThatThrownBy(() -> inventory.submit(T0, USER)).satisfies(this::invalidTransition);
		assertThatThrownBy(() -> inventory.complete(T0, USER, null)).satisfies(this::invalidTransition);

		InventorySession counting = counted();
		assertThatThrownBy(() -> counting.complete(T0, USER, null))
			.as("validation seulement après soumission").satisfies(this::invalidTransition);
	}

	@Test
	void aCompletedOrCancelledInventoryIsFrozen() {
		InventorySession completed = counted();
		completed.submit(T0, USER);
		completed.complete(T0, USER, null);
		InventorySession cancelled = draft();
		cancelled.cancel(T0, USER, "Erreur");

		for (InventorySession inventory : new InventorySession[] { completed, cancelled }) {
			assertThatThrownBy(() -> inventory.count(line(inventory, this.frame), 1, 1, T0, USER, null))
				.satisfies(this::invalidTransition);
			assertThatThrownBy(() -> inventory.cancel(T0, USER, null)).satisfies(this::invalidTransition);
			assertThatThrownBy(inventory::reopen).satisfies(this::invalidTransition);
			assertThatThrownBy(() -> inventory.addProduct(UUID.randomUUID(), "X", "X")).satisfies(this::invalidTransition);
		}
		assertThat(cancelled.getCancellationReason()).isEqualTo("Erreur");
	}

	private void invalidTransition(Throwable ex) {
		assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVENTORY_INVALID_TRANSITION);
	}

	private InventorySession draft() {
		InventorySession inventory = new InventorySession("INV-2026-000001", UUID.randomUUID(), InventoryScope.SELECTION,
				null, null);
		inventory.addProduct(this.frame, "MON-1", "Monture");
		inventory.addProduct(this.lens, "LEN-1", "Lentilles");
		return inventory;
	}

	private InventorySession counted() {
		InventorySession inventory = draft();
		inventory.start(T0, USER);
		inventory.count(line(inventory, this.frame), 10, 10, T0, USER, null);
		inventory.count(line(inventory, this.lens), 4, 4, T0, USER, null);
		return inventory;
	}

	private static InventoryLine line(InventorySession inventory, UUID productId) {
		return inventory.line(productId).orElseThrow();
	}

}
