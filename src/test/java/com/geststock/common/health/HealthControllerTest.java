package com.geststock.common.health;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.support.WebLayerTestConfiguration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
	private DataSource dataSource;

	@Test
	void returnsUpWithoutAuthenticationWhenDatabaseIsReachable() throws Exception {
		Connection connection = mock(Connection.class);
		given(connection.isValid(anyInt())).willReturn(true);
		given(this.dataSource.getConnection()).willReturn(connection);

		this.mvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.application").value("geststock-backend"))
			.andExpect(jsonPath("$.components.database").value("UP"))
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	void returns503WhenDatabaseIsUnreachable() throws Exception {
		given(this.dataSource.getConnection()).willThrow(new SQLException("connection refused"));

		this.mvc.perform(get("/api/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.components.database").value("DOWN"));
	}

}
