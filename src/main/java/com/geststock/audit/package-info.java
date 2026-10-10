/**
 * Journal d'audit métier : qui a fait quoi, dans quel module, sur quel objet, quand, d'où,
 * avec quel résultat et ce qui a changé.
 *
 * <ul>
 * <li>écriture : {@link com.geststock.audit.AuditService} (auteur relevé dans le contexte de
 * sécurité, jamais fourni par l'appelant) ; réussites dans la transaction de l'opération,
 * échecs des points d'entrée {@link com.geststock.audit.Audited} dans leur propre
 * transaction ;</li>
 * <li>consultation : {@code /api/audit-logs} (recherche, détail, statistiques, export CSV),
 * permission {@code audit:read} ; aucune écriture par l'API, table en ajout seul.</li>
 * </ul>
 */
package com.geststock.audit;
