package com.geststock.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Transforme un jeton d'accès validé en authentification. Volontairement distinct de
 * {@code Converter} : un bean {@code Converter} serait aussi enregistré par Spring MVC comme
 * convertisseur de paramètres de requête.
 */
@FunctionalInterface
public interface JwtAuthenticationResolver {

	AbstractAuthenticationToken resolve(Jwt jwt);

}
