package com.geststock.exception;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.support.WebLayerTestConfiguration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les deux codes 400 d'un paramètre de requête dans cette API, tels que les renverra {@code GET /catalog/changes}
 * pour {@code after} et {@code limit} (décision 3) :
 * <ul>
 * <li>{@code VALIDATION_FAILED} : valeur hors bornes refusée par Bean Validation ({@code @Min}, {@code @Max}),
 * avec le paramètre fautif dans {@code errors} ; le service du flux produit le même code
 * ({@code ChangeFeedRequestException}) ;</li>
 * <li>{@code INVALID_PARAMETER} : valeur non numérique, ou paramètre obligatoire absent, refusés avant toute
 * validation.</li>
 * </ul>
 */
@WebMvcTest(controllers = RequestParameterValidationTest.BoundedParameterController.class)
@Import({ WebLayerTestConfiguration.class, RequestParameterValidationTest.BoundedParameterController.class })
@WithMockUser
class RequestParameterValidationTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void aParameterOutsideItsBoundsIsAValidationFailure() throws Exception {
		this.mvc.perform(get("/test/params/bounded").param("after", "0").param("limit", "501"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
			.andExpect(jsonPath("$.errors", hasSize(1)))
			.andExpect(jsonPath("$.errors[0].field").value("limit"));
		this.mvc.perform(get("/test/params/bounded").param("after", "-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
			.andExpect(jsonPath("$.errors[0].field").value("after"));
		this.mvc.perform(get("/test/params/bounded").param("after", "0").param("limit", "500"))
			.andExpect(status().isOk());
	}

	@Test
	void aNonNumericOrMissingParameterIsAnInvalidParameter() throws Exception {
		this.mvc.perform(get("/test/params/bounded").param("after", "0").param("limit", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.name()))
			.andExpect(jsonPath("$.message").value("Paramètre invalide : limit."));
		this.mvc.perform(get("/test/params/bounded").param("after", "x"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.name()));
		this.mvc.perform(get("/test/params/bounded"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.name()))
			.andExpect(jsonPath("$.message").value("Paramètre requis : after."));
	}

	@RestController
	@RequestMapping("/test/params")
	static class BoundedParameterController {

		@GetMapping("/bounded")
		void bounded(@RequestParam @Min(0) long after, @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
		}

	}

}
