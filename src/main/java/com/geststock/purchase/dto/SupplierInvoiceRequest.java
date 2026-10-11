package com.geststock.purchase.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Facture du fournisseur pour une commande. Contrat du front : {@code {invoiceNumber, invoiceDateIso}}.
 * Le montant n'est pas saisi : c'est la valeur reçue.
 *
 * @param invoiceDate date de la facture ({@code invoiceDateIso} accepté) : ni future, ni antérieure à
 *                    la commande
 */
@Schema(name = "SupplierInvoiceRequest")
public record SupplierInvoiceRequest(
		@NotBlank @Size(max = 50) @Schema(example = "FA-ESS-2026-0412") String invoiceNumber,
		@NotNull @JsonAlias("invoiceDateIso") @Schema(example = "2026-10-10") LocalDate invoiceDate) {
}
