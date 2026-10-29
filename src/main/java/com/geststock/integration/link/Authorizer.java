package com.geststock.integration.link;

import java.util.UUID;

/**
 * Personne de l'entreprise qui approuve une liaison (permission {@code integration:manage}).
 *
 * @param userId   {@code app_user.id} dans la base de l'entreprise
 * @param username nom d'utilisateur (journal, affichage)
 */
public record Authorizer(UUID userId, String username) {

}
