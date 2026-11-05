package com.geststock.integration.link;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.ChannelPrices;
import com.geststock.tenancy.TenantContext;

/**
 * Liste croisée des propositions de prix en attente, toutes liaisons de l'entreprise confondues (lot 3, étape G24) :
 * permission {@code integration:price-approve} seule suffit (pas besoin de {@code integration:manage}). Les liaisons
 * révoquées sont exclues ; les liaisons suspendues sont incluses avec leur statut. Seul {@code PROPOSED} est accepté
 * pour l'instant ({@code 400 INVALID_PARAMETER} pour tout autre statut).
 */
@RestController
@RequestMapping("/api/price-proposals")
@Tag(name = "Intégration — liaisons boutique")
class PriceProposalsController {

	/** Seul statut accepté pour l'instant. */
	private static final String PROPOSED = "PROPOSED";

	/** Ordre fixe (les plus récentes d'abord) : aucun tri demandé par le client. */
	private static final SortWhitelist NO_SORT = new SortWhitelist(Map.of(), Sort.unsorted());

	private final ChannelPrices prices;

	private final LinkRegistry links;

	PriceProposalsController(ChannelPrices prices, LinkRegistry links) {
		this.prices = prices;
		this.links = links;
	}

	@GetMapping
	@RequiresPermission(Permissions.INTEGRATION_PRICE_APPROVE)
	@Operation(operationId = "listAllPriceProposals",
			summary = "Lister les propositions de prix en attente, toutes liaisons confondues",
			description = "Propositions PROPOSED de toutes les liaisons non révoquées de l'entreprise, les plus récentes "
					+ "d'abord. Chaque item inclut linkId, shopName et linkStatus en plus des champs habituels. Seul le "
					+ "statut PROPOSED est accepté (400 INVALID_PARAMETER pour tout autre).")
	ResponseEntity<PageResponse<ChannelPrices.CrossLinkProposalView>> list(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		String wanted = status != null ? status : PROPOSED;
		if (!PROPOSED.equals(wanted)) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
					"Paramètre invalide : seul le statut PROPOSED est accepté.");
		}
		UUID company = TenantContext.require().companyId();
		Map<UUID, Link> linkMap = this.links.listForCompany(company).stream()
			.filter(link -> link.status() != LinkStatus.REVOKED)
			.collect(Collectors.toMap(Link::id, Function.identity()));
		PageQuery query = new PageQuery(page, size, null);
		return PageResponse.toResponse(this.prices.proposalsAcrossLinks(linkMap, wanted, query.toPageable(NO_SORT)), query);
	}

}
