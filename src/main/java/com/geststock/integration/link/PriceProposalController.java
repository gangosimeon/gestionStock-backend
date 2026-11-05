package com.geststock.integration.link;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.ChannelPrices;
import com.geststock.security.CurrentUser;
import com.geststock.tenancy.TenantContext;

/**
 * Approbation des prix proposés par une boutique liée (lot 3, étape G22b ; D2, C6, v16-D3 ; décision P9), sur la
 * chaîne principale : personnes connectées de l'entreprise, permission {@code integration:price-approve} (G21). Ce ne
 * sont pas des routes du contrat d'intégration. La liaison doit appartenir à l'entreprise de la personne connectée :
 * sinon, comme une liaison inconnue, {@code 404 LINK_NOT_FOUND} ; une proposition d'une autre liaison :
 * {@code 404 PRICE_PROPOSAL_NOT_FOUND}. Une décision sur une proposition qui n'est plus en attente :
 * {@code 409 PRICE_PROPOSAL_NOT_PENDING}. Le motif d'un refus est masqué dans les journaux ({@code toString}).
 */
@RestController
@RequestMapping("/api/integration-links/{linkId}/price-proposals")
@Tag(name = "Intégration — liaisons boutique")
class PriceProposalController {

	/** Statuts d'une proposition (contrat {@code ChannelPrice.pending.status}). */
	private static final Set<String> STATUSES = Set.of("PROPOSED", "APPROVED", "REJECTED", "SUPERSEDED", "WITHDRAWN");

	/** Ordre fixe (les plus récentes d'abord) : aucun tri demandé par le client. */
	private static final SortWhitelist NO_SORT = new SortWhitelist(Map.of(), Sort.unsorted());

	private final ChannelPrices prices;

	private final LinkRegistry links;

	PriceProposalController(ChannelPrices prices, LinkRegistry links) {
		this.prices = prices;
		this.links = links;
	}

	@GetMapping
	@RequiresPermission(Permissions.INTEGRATION_PRICE_APPROVE)
	@Operation(operationId = "listPriceProposals", summary = "Lister les propositions de prix d'une liaison",
			description = "Propositions de la liaison dans le statut demandé (PROPOSED par défaut ; APPROVED, REJECTED, "
					+ "SUPERSEDED, WITHDRAWN), les plus récentes d'abord : produit, prix proposé, devise, prix de référence "
					+ "au moment de la proposition et prix de référence courant, note de la boutique. Sans page ni size : "
					+ "liste complète.")
	ResponseEntity<PageResponse<ChannelPrices.ProposalView>> list(@PathVariable UUID linkId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		own(linkId);
		String wanted = status != null ? status : "PROPOSED";
		if (!STATUSES.contains(wanted)) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
					"Paramètre invalide : status vaut PROPOSED, APPROVED, REJECTED, SUPERSEDED ou WITHDRAWN.");
		}
		PageQuery query = new PageQuery(page, size, null);
		return PageResponse.toResponse(this.prices.proposals(linkId, wanted, query.toPageable(NO_SORT)), query);
	}

	@PostMapping("/{proposalId}/approve")
	@RequiresPermission(Permissions.INTEGRATION_PRICE_APPROVE)
	@Operation(operationId = "approvePriceProposal", summary = "Approuver une proposition de prix",
			description = "La proposition, encore en attente, devient le prix approuvé (seul commercialisable) de la "
					+ "boutique ; le précédent reste dans l'historique. PRICE_PROPOSAL_NOT_PENDING (409) si elle a déjà été "
					+ "décidée ou remplacée par une proposition plus récente. LINK_PROPOSAL_REVOKED (409) si la liaison est "
					+ "révoquée (G24).")
	ChannelPrices.ProposalView approve(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID linkId,
			@PathVariable UUID proposalId) {
		Link link = own(linkId);
		requireNotRevoked(link);
		// Le garde re-lit la liaison sous le verrou de tête (ChannelPrices.decide) : fenêtre de course minimisée (G24).
		return this.prices.approve(linkId, proposalId, user.id(), () -> requireNotRevoked(own(linkId)));
	}

	@PostMapping("/{proposalId}/reject")
	@RequiresPermission(Permissions.INTEGRATION_PRICE_APPROVE)
	@Operation(operationId = "rejectPriceProposal", summary = "Refuser une proposition de prix",
			description = "Motif obligatoire (1 à 500 caractères), visible de la boutique. PRICE_PROPOSAL_NOT_PENDING "
					+ "(409) si la proposition a déjà été décidée ou remplacée. LINK_PROPOSAL_REVOKED (409) si la liaison "
					+ "est révoquée (G24).")
	ChannelPrices.ProposalView reject(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID linkId,
			@PathVariable UUID proposalId, @Valid @RequestBody Rejection request) {
		Link link = own(linkId);
		requireNotRevoked(link);
		// Le garde re-lit la liaison sous le verrou de tête (ChannelPrices.decide) : fenêtre de course minimisée (G24).
		return this.prices.reject(linkId, proposalId, user.id(), request.reason().strip(),
				() -> requireNotRevoked(own(linkId)));
	}

	/** Liaison de l'entreprise courante seulement ; une autre entreprise reçoit la réponse d'une liaison inconnue. */
	private Link own(UUID linkId) {
		UUID company = TenantContext.require().companyId();
		return this.links.find(linkId).filter(link -> link.companyId().equals(company))
			.orElseThrow(() -> new LinkConsentException(ErrorCode.LINK_NOT_FOUND));
	}

	/** Liaison révoquée : toute décision de prix est bloquée (G24). */
	private static void requireNotRevoked(Link link) {
		if (link.status() == LinkStatus.REVOKED) {
			throw new ConflictException(ErrorCode.LINK_PROPOSAL_REVOKED, null);
		}
	}

	/** Refus : motif obligatoire, masqué dans les journaux. */
	record Rejection(@NotBlank @Size(max = 500) @ControlCharacters.FreeText String reason) {

		@Override
		public String toString() {
			return "Rejection[***]";
		}

	}

}
