package com.geststock.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.exception.ErrorCode;

/** Réponse 403 au format {@code ApiError} (utilisateur authentifié sans la permission requise). */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final ApiErrorFactory errorFactory;

	private final ApiErrorWriter errorWriter;

	public RestAccessDeniedHandler(ApiErrorFactory errorFactory, ApiErrorWriter errorWriter) {
		this.errorFactory = errorFactory;
		this.errorWriter = errorWriter;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		this.errorWriter.write(response, this.errorFactory.create(ErrorCode.ACCESS_DENIED, null, request));
	}

}
