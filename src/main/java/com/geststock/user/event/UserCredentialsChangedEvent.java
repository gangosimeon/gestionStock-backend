package com.geststock.user.event;

import java.util.UUID;

/**
 * Publié dans la transaction qui change le mot de passe d'un utilisateur : les sessions
 * existantes doivent être fermées (révocation des refresh tokens par le module sécurité).
 */
public record UserCredentialsChangedEvent(UUID userId) {
}
