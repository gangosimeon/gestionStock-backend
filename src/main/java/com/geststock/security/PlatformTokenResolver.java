package com.geststock.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** Conversion d'un jeton Super Admin ({@code typ=platform}) : implémentée par la plateforme. */
public interface PlatformTokenResolver {

	AbstractAuthenticationToken resolve(Jwt jwt);

}
