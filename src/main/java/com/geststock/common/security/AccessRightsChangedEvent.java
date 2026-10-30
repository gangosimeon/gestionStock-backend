package com.geststock.common.security;

/**
 * Changement qui peut retirer des droits à des utilisateurs de l'entreprise courante : rôles ou dépôts d'un
 * utilisateur, permissions d'un rôle, dépôt principal (accès des utilisateurs sans affectation). La désactivation d'un
 * utilisateur a son propre événement ({@code UserDeactivatedEvent}). Publié dans la transaction du changement ; les
 * auditeurs qui en dépendent l'écoutent après le commit (lot 2, étape G4b : réévaluation des liaisons boutique).
 *
 * @param change nature du changement (journaux)
 */
public record AccessRightsChangedEvent(String change) {
}
