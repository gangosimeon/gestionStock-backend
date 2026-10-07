package com.geststock.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Écrit une {@link ApiError} directement dans la réponse servlet. Utilisé hors de Spring MVC
 * (filtres de sécurité), là où {@code @RestControllerAdvice} ne s'applique pas.
 */
@Component
public class ApiErrorWriter {

	private final JsonMapper jsonMapper;

	public ApiErrorWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void write(HttpServletResponse response, ApiError error) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(error.status());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		this.jsonMapper.writeValue(response.getOutputStream(), error);
	}

}
