package com.geststock.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.exception.ErrorCode;

/**
 * Réponse 401 au format {@code ApiError}, pour toute requête protégée sans jeton valide :
 * <ul>
 * <li>pas de jeton → {@code UNAUTHENTICATED} ;</li>
 * <li>jeton présent mais invalide, expiré, ou compte désactivé depuis → {@code INVALID_TOKEN}.</li>
 * </ul>
 * Le front réagit à ce statut en renouvelant la session, puis en déconnectant l'utilisateur.
 * Le détail du refus n'est jamais renvoyé au client.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ApiErrorFactory errorFactory;

	private final ApiErrorWriter errorWriter;

	public RestAuthenticationEntryPoint(ApiErrorFactory errorFactory, ApiErrorWriter errorWriter) {
		this.errorFactory = errorFactory;
		this.errorWriter = errorWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		boolean invalidToken = ex instanceof OAuth2AuthenticationException;
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
		ErrorCode code = invalidToken ? ErrorCode.INVALID_TOKEN : ErrorCode.UNAUTHENTICATED;
		this.errorWriter.write(response, this.errorFactory.create(code, null, request));
	}

}
