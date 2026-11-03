package com.geststock.integration.link;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.tenancy.TenantRef;

/**
 * Rattrapages du lot 2 par la maintenance de l'intégration (lot 3, G20f-1) : ce que les traitements « au mieux »
 * du lot 2 laissent à faire quand ils échouent après un commit. Seul point d'entrée de la maintenance vers eux.
 * Aucune suppression : le drapeau n'est que levé, une liaison n'est que suspendue (réactivable).
 */
@Component
public class LinkCatchUp {

	private final LinkPublication publication;

	private final LinkAuthorizerWatcher authorizers;

	LinkCatchUp(LinkPublication publication, LinkAuthorizerWatcher authorizers) {
		this.publication = publication;
		this.authorizers = authorizers;
	}

	/**
	 * Drapeau de publication levé s'il est baissé alors que l'entreprise a une liaison active ou suspendue (G13) ;
	 * jamais baissé.
	 * @return vrai s'il a été levé : des changements ont pu ne pas être publiés, la boutique doit relire un instantané
	 */
	public boolean restorePublication(UUID companyId) {
		return this.publication.raiseIfLinked(companyId);
	}

	/**
	 * Réévaluation des personnes responsables des liaisons actives de l'entreprise (G4b), comme après un changement
	 * de droits : liaison suspendue ({@code AUTHORIZER_LOST_ACCESS}) si sa responsable a perdu ses droits. Idempotente.
	 * @return liaisons suspendues
	 */
	public int reassessAuthorizers(TenantRef company) {
		return this.authorizers.reassess(company);
	}

}
