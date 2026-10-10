package com.geststock.sale;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.sale.dto.SaleResponse;

/**
 * Ventes d'un client (contrat front {@code GET /api/customers/{id}/sales}). Servi par le module
 * ventes : le module client ne dépend pas des ventes.
 */
@RestController
@RequestMapping("/api/customers/{customerId}/sales")
@Tag(name = "Clients")
class CustomerSalesController {

	private final SaleService sales;

	CustomerSalesController(SaleService sales) {
		this.sales = sales;
	}

	@GetMapping
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Ventes d'un client", description = "Dans tous les dépôts que l'utilisateur peut consulter (tous avec warehouse:all), "
			+ "des plus récentes aux plus anciennes.")
	ResponseEntity<PageResponse<SaleResponse>> list(@PathVariable UUID customerId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number | total, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.sales.customerSales(customerId, query), query);
	}

}
