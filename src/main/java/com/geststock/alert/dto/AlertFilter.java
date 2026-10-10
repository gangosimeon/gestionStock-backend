package com.geststock.alert.dto;

import java.util.List;

import com.geststock.alert.AlertCategory;
import com.geststock.alert.AlertSeverity;
import com.geststock.alert.AlertType;

/**
 * Critères des alertes (tous facultatifs, répétables : {@code ?type=OUT_OF_STOCK&type=BELOW_MINIMUM}).
 *
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record AlertFilter(List<AlertCategory> category, List<AlertType> type, List<AlertSeverity> severity,
		Boolean allWarehouses) {
}
