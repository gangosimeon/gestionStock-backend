package com.geststock.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Création ou modification d'un fournisseur.
 *
 * <p>Le front envoie {@code name, phone, email, address, deliveryLeadTimeDays} : ces champs
 * sont remplacés à chaque modification (absent = effacé). Les champs que le front ne connaît
 * pas encore ({@code code, contactName, taxId, paymentTermsDays, note}) sont <b>conservés
 * s'ils sont absents</b> ; une chaîne vide les efface (sauf le code, obligatoire). Ainsi
 * l'écran actuel ne perd jamais ce qu'un autre client a saisi.
 */
@Schema(name = "SupplierRequest")
public record SupplierRequest(
		@Size(max = 20) @Pattern(regexp = "^([A-Za-z0-9][A-Za-z0-9-]*)?$", message = "lettres, chiffres et tirets uniquement")
		@Schema(example = "FOU-ESSILOR", description = "Facultatif : généré si absent (FOU-0001). Converti en majuscules.")
		String code,
		@NotBlank @Size(max = 200) @Schema(example = "Essilor Distribution CI") String name,
		@Size(max = 150) @Schema(example = "Awa Koné") String contactName,
		@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$", message = "numéro de téléphone invalide")
		@Schema(example = "+225 20 25 00 10") String phone,
		@Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") @Size(max = 254)
		@Schema(example = "commandes@essilor.ci") String email,
		@Size(max = 400) @Schema(example = "Zone Ind. Yopougon, Abidjan") String address,
		@Size(max = 50) @Schema(example = "CI-1234567-A", description = "Identifiant fiscal (NCC)") String taxId,
		@Min(0) @Max(365) @Schema(example = "5", description = "Délai de livraison en jours (0 si absent à la création)")
		Integer deliveryLeadTimeDays,
		@Min(0) @Max(365) @Schema(example = "30", description = "Délai de paiement en jours, 0 = comptant")
		Integer paymentTermsDays,
		@Size(max = 1000) String note) {
}
