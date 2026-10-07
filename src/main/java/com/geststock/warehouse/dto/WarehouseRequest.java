package com.geststock.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.warehouse.WarehouseType;

/**
 * Création ou modification d'un dépôt. Le front n'envoie aujourd'hui que {@code name} :
 * le code est alors généré (création) ou conservé (modification), le type vaut STORE.
 */
@Schema(name = "WarehouseRequest")
public record WarehouseRequest(
		@Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]*$", message = "lettres, chiffres et tirets uniquement")
		@Schema(example = "MAG-02", description = "Facultatif : généré si absent (DEP-nn). Converti en majuscules.")
		String code,
		@NotBlank @Size(min = 2, max = 100) @Schema(example = "Magasin Plateau") String name,
		@Schema(description = "STORE (par défaut à la création) ou WAREHOUSE") WarehouseType type,
		@Size(max = 255) @Schema(example = "Avenue Chardy, Plateau, Abidjan") String address,
		@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$", message = "numéro de téléphone invalide") String phone) {
}
