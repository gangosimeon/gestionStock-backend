package com.geststock.stock.dto;

import jakarta.validation.constraints.Size;

/** Retour d'un transfert au dépôt d'origine ; motif facultatif. */
public record ReturnStockTransferRequest(@Size(max = 500) String reason) {
}
