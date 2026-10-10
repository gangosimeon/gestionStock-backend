package com.geststock.customer.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.customer.CustomerType;
import com.geststock.customer.PriceCategory;

/**
 * Création ou modification d'un client. Contrat du front : {@code {name, phone, email, creditLimit}}.
 *
 * <p>Modification : {@code name}, {@code phone} et {@code email} sont remplacés (absent =
 * effacé) ; {@code creditLimit} et les champs que le front ne connaît pas encore sont
 * <b>conservés s'ils sont absents</b> (une chaîne vide efface un texte). Accorder ou changer
 * un plafond de crédit exige la permission {@code customer:credit}.
 */
@Schema(name = "CustomerRequest")
public record CustomerRequest(
		@Size(max = 20) @Pattern(regexp = "^([A-Za-z0-9][A-Za-z0-9-]*)?$", message = "lettres, chiffres et tirets uniquement")
		@Schema(description = "Facultatif : généré si absent (CLI-000001). Converti en majuscules.") String code,
		@Schema(description = "INDIVIDUAL (défaut) ou COMPANY") CustomerType type,
		@NotBlank @Size(max = 200) @Schema(example = "Clinique Vision Plus") String name,
		@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$", message = "numéro de téléphone invalide")
		@Schema(example = "+225 20 21 00 55") String phone,
		@Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") @Size(max = 254) @Schema(example = "vision.plus@clinique.ci")
		String email,
		@Size(max = 400) String address,
		@Size(max = 100) @Schema(example = "Abidjan") String city,
		@Size(max = 50) @Schema(description = "Identifiant fiscal (NCC) d'une entreprise") String taxId,
		@Schema(description = "Tarif par défaut : RETAIL (défaut) ou WHOLESALE") PriceCategory priceCategory,
		@DecimalMin("0") @Digits(integer = 12, fraction = 2)
		@Schema(example = "500000", description = "Plafond de dette ; 0 = aucun crédit (défaut)") BigDecimal creditLimit,
		@Min(0) @Max(365) @Schema(description = "Délai de paiement des ventes à crédit, en jours") Integer paymentTermsDays,
		@Size(max = 1000) String note) {
}
