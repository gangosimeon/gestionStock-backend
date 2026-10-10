package com.geststock.alert;

import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.alert.dto.AlertFilter;
import com.geststock.alert.dto.AlertResponse;
import com.geststock.alert.dto.AlertSummaryResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Alertes du tableau de bord. Accessible à quiconque consulte au moins un domaine surveillé ;
 * chacun ne voit que les alertes des domaines qu'il peut consulter.
 */
@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alertes")
@PreAuthorize("hasAnyAuthority('stock:read', 'purchase:read', 'customer:read', 'cash:read', 'inventory:read')")
class AlertController {

	private final AlertService alerts;

	private final WarehouseAccess warehouseAccess;

	AlertController(AlertService alerts, WarehouseAccess warehouseAccess) {
		this.alerts = alerts;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@Operation(summary = "Lister les alertes",
			description = "Alertes calculées à l'instant pour le dépôt courant (les alertes clients ne dépendent pas du "
					+ "dépôt), de la plus grave à la moins grave ; filtres category, type, severity (répétables). "
					+ "Sans page ni size : liste complète. allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<AlertResponse>> list(@ParameterObject AlertFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		PageQuery query = new PageQuery(page, size, null);
		return PageResponse.toResponse(this.alerts.search(filter, scope(filter.allWarehouses(), warehouse), query),
				query);
	}

	@GetMapping("/summary")
	@Operation(summary = "Synthèse des alertes (tableau de bord)",
			description = "Compteurs par gravité, domaine et type, nombre d'alertes de stock (ruptures + seuils "
					+ "minimum atteints) et les 10 alertes les plus graves.")
	AlertSummaryResponse summary(@RequestParam(required = false) Boolean allWarehouses,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.alerts.summary(scope(allWarehouses, warehouse));
	}

	@GetMapping("/settings")
	@Operation(summary = "Seuils des alertes")
	AlertSettings settings() {
		return this.alerts.currentValues();
	}

	@PutMapping("/settings")
	@Audited(action = "UPDATE", entityType = "ALERT_SETTING", idParam = "")
	@RequiresPermission(Permissions.ALERT_CONFIGURE)
	@Operation(summary = "Modifier les seuils des alertes",
			description = "Effet immédiat, sans redémarrage ; tracé dans le journal d'audit. 0 désactive l'alerte "
					+ "concernée. Le seuil minimum propre à chaque produit se règle sur le produit (alertThreshold).")
	AlertSettings updateSettings(@Valid @RequestBody AlertSettings request) {
		return this.alerts.updateSettings(request);
	}

	private UUID scope(Boolean allWarehouses, WarehouseContext warehouse) {
		if (Boolean.TRUE.equals(allWarehouses)) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			return null;
		}
		return warehouse.id();
	}

}
