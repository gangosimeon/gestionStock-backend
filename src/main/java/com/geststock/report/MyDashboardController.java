package com.geststock.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.security.ActorIdentity;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.report.dto.MyDayResponse;

/**
 * Tableau de bord du vendeur (caissier) : sa propre journée, sans {@code report:read}. Le tableau
 * de bord du magasin ({@code /api/dashboard}) reste réservé à {@code report:read}.
 */
@RestController
@Tag(name = "Tableau de bord")
class MyDashboardController {

	private final DashboardService dashboard;

	MyDashboardController(DashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/api/dashboard/me")
	@RequiresPermission(Permissions.SALE_CREATE)
	@Operation(summary = "Ma journée de vente",
			description = "Mes ventes du jour (fuseau de l'entreprise), tous dépôts : nombre, total, encaissé, à crédit, "
					+ "annulations. Ni marge ni chiffres du magasin.")
	MyDayResponse myDay() {
		return this.dashboard.myDay(ActorIdentity.currentUserId().orElseThrow());
	}

}
