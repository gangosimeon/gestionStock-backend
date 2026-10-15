package com.geststock.customer.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Situation de crédit d'un client.
 *
 * <p>Le solde et le plafond sont ceux du client pour toute l'entreprise, tous dépôts confondus :
 * ce n'est pas la dette née dans le dépôt courant.
 *
 * @param balance                solde dû, tous dépôts (débits − crédits du compte)
 * @param availableCredit        plafond − solde dû, au minimum 0
 * @param otherWarehousesBalance part du solde née hors des dépôts de l'utilisateur, sans détail ;
 *                               {@code null} s'il voit tous les dépôts (relevé complet)
 */
@Schema(name = "CustomerAccount")
public record CustomerAccountResponse(
		UUID customerId,
		BigDecimal creditLimit,
		@Schema(description = "Solde dû du client, tous dépôts confondus") BigDecimal balance,
		BigDecimal availableCredit,
		int paymentTermsDays,
		boolean active,
		@Schema(description = "Ligne « Autres dépôts » : solde net des écritures hors de vos dépôts, sans détail ; "
				+ "absent avec warehouse:all") BigDecimal otherWarehousesBalance) {
}
