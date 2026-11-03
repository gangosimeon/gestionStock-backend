package com.geststock.integration.link;

import java.util.UUID;

/**
 * Liaison active ou suspendue d'une entreprise, vue par la maintenance (lot 3, G20c ; plan § 33.5). Les âges sont
 * calculés par la base plateforme ({@code now()}), qui stocke les dates mesurées.
 *
 * @param id                     liaison
 * @param status                 {@link LinkStatus#ACTIVE} ou {@link LinkStatus#SUSPENDED}
 * @param sinceActivationSeconds temps écoulé depuis l'activation ({@code activated_at})
 * @param sinceSuspensionSeconds temps écoulé depuis la suspension ({@code suspended_at}) ; 0 si la liaison est active
 */
public record RetainedLink(UUID id, LinkStatus status, long sinceActivationSeconds, long sinceSuspensionSeconds) {
}
