package com.geststock.exception;

import java.util.List;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remplace le {@code BasicErrorController} de Spring Boot : les erreurs produites hors de
 * Spring MVC (filtres, conteneur servlet) ont le même format {@link ApiError} que les autres.
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

	private final ApiErrorFactory errorFactory;

	public ApiErrorController(ApiErrorFactory errorFactory) {
		this.errorFactory = errorFactory;
	}

	@RequestMapping("${server.error.path:/error}")
	ResponseEntity<ApiError> error(HttpServletRequest request) {
		HttpStatusCode status = resolveStatus(request);
		Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		String path = uri != null ? uri.toString() : ApiErrorFactory.path(request);
		ApiError body = this.errorFactory.create(status, ErrorCode.fromStatus(status), null, List.of(), Map.of(), path);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	private static HttpStatusCode resolveStatus(HttpServletRequest request) {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (code instanceof Integer value) {
			return HttpStatusCode.valueOf(value);
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

}
