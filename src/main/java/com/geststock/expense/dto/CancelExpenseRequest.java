package com.geststock.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Annulation d'une dépense : le motif reste attaché à la dépense. */
@Schema(name = "CancelExpenseRequest")
public record CancelExpenseRequest(
		@NotBlank @Size(max = 500) @Schema(example = "Montant saisi deux fois") String reason) {
}
