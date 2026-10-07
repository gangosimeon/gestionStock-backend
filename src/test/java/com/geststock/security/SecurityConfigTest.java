package com.geststock.security;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.support.WebLayerTestConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Règles HTTP du socle de sécurité : 401 au format standard, CORS, absence de session. */
@WebMvcTest(controllers = SecurityConfigTest.ProtectedTestController.class)
@Import({ WebLayerTestConfiguration.class, SecurityConfigTest.ProtectedTestController.class })
class SecurityConfigTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:4200";

	@Autowired
	private MockMvc mvc;

	@Test
	void protectedRouteWithoutTokenReturns401InStandardFormat() throws Exception {
		this.mvc.perform(get("/api/protected"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.message").value("Authentification requise."))
			.andExpect(jsonPath("$.path").value("/api/protected"))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void corsPreflightFromAllowedOriginSucceedsWithoutAuthentication() throws Exception {
		this.mvc.perform(options("/api/protected")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, X-Warehouse-Id"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
	}

	@Test
	void corsPreflightFromUnknownOriginIsRejected() throws Exception {
		this.mvc.perform(options("/api/protected")
			.header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void corsExposesTraceIdHeaderToTheBrowser() throws Exception {
		this.mvc.perform(get("/api/protected").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
					org.hamcrest.Matchers.containsString("X-Trace-Id")));
	}

	@RestController
	static class ProtectedTestController {

		@GetMapping("/api/protected")
		String protectedResource() {
			return "secret";
		}

	}

}
