package com.geststock.exception;

import java.sql.SQLException;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.web.TraceIdFilter;
import com.geststock.support.WebLayerTestConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat du format d'erreur : chaque type d'exception produit une {@link ApiError}
 * complète, avec un {@code message} lisible et sans fuite d'information technique.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorTestController.class)
@Import({ WebLayerTestConfiguration.class, GlobalExceptionHandlerTest.ErrorTestController.class,
		GlobalExceptionHandlerTest.ConstraintMappingConfiguration.class })
@WithMockUser
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void apiExceptionProducesCompleteErrorBody() throws Exception {
		this.mvc.perform(get("/test/errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:geststock:error:resource-not-found"))
			.andExpect(jsonPath("$.title").value("Not Found"))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("Produit introuvable."))
			.andExpect(jsonPath("$.detail").value("Produit introuvable."))
			.andExpect(jsonPath("$.errors", hasSize(0)))
			.andExpect(jsonPath("$.path").value("/test/errors/not-found"))
			.andExpect(jsonPath("$.timestamp", startsWith("20")))
			.andExpect(jsonPath("$.traceId", notNullValue()))
			.andExpect(header().exists(TraceIdFilter.HEADER));
	}

	@Test
	void businessRuleExceptionReturns422WithDetails() throws Exception {
		this.mvc.perform(get("/test/errors/business"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
			.andExpect(jsonPath("$.message").value("Stock insuffisant."))
			.andExpect(jsonPath("$.details.available").value(2));
	}

	@Test
	void bodyValidationListsFieldErrorsInFrench() throws Exception {
		this.mvc.perform(post("/test/errors/validation").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"\", \"quantity\": 0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors", hasSize(2)))
			.andExpect(jsonPath("$.message", startsWith("Données invalides : ")))
			.andExpect(jsonPath("$.message", containsString("autre(s) erreur(s)")))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("ne doit pas être vide"));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		this.mvc.perform(post("/test/errors/validation").contentType(MediaType.APPLICATION_JSON).content("{oops"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void parameterTypeMismatchReturns400() throws Exception {
		this.mvc.perform(get("/test/errors/typed").param("page", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
			.andExpect(jsonPath("$.message").value("Paramètre invalide : page."));
	}

	@Test
	void missingParameterReturns400() throws Exception {
		this.mvc.perform(get("/test/errors/typed"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Paramètre requis : page."));
	}

	@Test
	void optimisticLockReturns409() throws Exception {
		this.mvc.perform(get("/test/errors/optimistic-lock"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	void mappedConstraintViolationReturnsModuleCode() throws Exception {
		this.mvc.perform(get("/test/errors/unique-constraint"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value(ErrorCode.CONFLICT.defaultMessage()));
	}

	@Test
	void unmappedConstraintViolationDoesNotLeakSql() throws Exception {
		this.mvc.perform(get("/test/errors/other-constraint"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
			.andExpect(content().string(not(containsString("duplicate key"))));
	}

	@Test
	void unexpectedExceptionReturnsGeneric500WithoutLeakingDetails() throws Exception {
		this.mvc.perform(get("/test/errors/unexpected"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()))
			.andExpect(content().string(not(containsString("secret"))));
	}

	@Test
	void accessDeniedIsHandledBySpringSecurityWith403() throws Exception {
		this.mvc.perform(get("/test/errors/forbidden"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
			.andExpect(jsonPath("$.message").value("Accès refusé."));
	}

	@Test
	void unknownRouteReturns404() throws Exception {
		this.mvc.perform(get("/api/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void unsupportedMethodReturns405() throws Exception {
		this.mvc.perform(put("/test/errors/not-found"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
			.andExpect(header().string("Allow", containsString("GET")));
	}

	@Test
	void validIncomingTraceIdIsPropagated() throws Exception {
		this.mvc.perform(get("/test/errors/not-found").header(TraceIdFilter.HEADER, "front-trace-1234"))
			.andExpect(header().string(TraceIdFilter.HEADER, "front-trace-1234"))
			.andExpect(jsonPath("$.traceId").value("front-trace-1234"));
	}

	// --- Contrôleur de test ----------------------------------------------------------------

	record SampleRequest(@NotBlank String name, @Positive int quantity) {
	}

	@RestController
	@RequestMapping("/test/errors")
	static class ErrorTestController {

		@GetMapping("/not-found")
		void notFound() {
			throw new NotFoundException("Produit introuvable.");
		}

		@GetMapping("/business")
		void business() {
			throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION, "Stock insuffisant.",
					Map.of("available", 2));
		}

		@PostMapping("/validation")
		void validation(@Valid @RequestBody SampleRequest request) {
		}

		@GetMapping("/typed")
		void typed(@RequestParam int page) {
		}

		@GetMapping("/optimistic-lock")
		void optimisticLock() {
			throw new ObjectOptimisticLockingFailureException(Object.class, "id");
		}

		@GetMapping("/unique-constraint")
		void uniqueConstraint() {
			throw constraintViolation("uk_test_name");
		}

		@GetMapping("/other-constraint")
		void otherConstraint() {
			throw constraintViolation("uk_unknown");
		}

		@GetMapping("/unexpected")
		void unexpected() {
			throw new IllegalStateException("secret internal detail");
		}

		@GetMapping("/forbidden")
		void forbidden() {
			throw new AccessDeniedException("denied");
		}

		private static DataIntegrityViolationException constraintViolation(String constraint) {
			return new DataIntegrityViolationException("duplicate key",
					new org.hibernate.exception.ConstraintViolationException("duplicate key value",
							new SQLException("duplicate key"), constraint));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConstraintMappingConfiguration {

		@Bean
		ConstraintErrorMapping testConstraintMapping() {
			return () -> Map.of("UK_TEST_NAME", ErrorCode.CONFLICT);
		}

	}

}
