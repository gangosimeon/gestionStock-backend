package com.geststock.common.health;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.platform.PlatformDb;
import com.geststock.support.WebLayerTestConfiguration;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/health} : public, 200 si la base répond, 503 sinon. */
@WebMvcTest(controllers = HealthController.class)
@Import(WebLayerTestConfiguration.class)
class HealthControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PlatformDb platform;

	@Test
	void returnsUpWithoutAuthenticationWhenDatabaseIsReachable() throws Exception {
		given(this.platform.isReachable()).willReturn(true);

		this.mvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.application").value("geststock-backend"))
			.andExpect(jsonPath("$.components.database").value("UP"))
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	void returns503WhenDatabaseIsUnreachable() throws Exception {
		given(this.platform.isReachable()).willReturn(false);

		this.mvc.perform(get("/api/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.components.database").value("DOWN"));
	}

}
