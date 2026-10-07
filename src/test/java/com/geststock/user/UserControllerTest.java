package com.geststock.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.support.WebLayerTestConfiguration;
import com.geststock.user.dto.UserResponse;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrat HTTP de {@code /api/users} : droits, validation, forme JSON attendue par le front. */
@WebMvcTest(controllers = UserController.class)
@Import(WebLayerTestConfiguration.class)
class UserControllerTest {

	private static final UUID ID = UUID.fromString("0199b0a0-0000-7000-8000-000000000001");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UserService service;

	@Test
	void requiresAuthentication() throws Exception {
		this.mvc.perform(get("/api/users"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verifyNoInteractions(this.service);
	}

	@Test
	@WithMockUser(authorities = "sale:create")
	void requiresUserManagePermission() throws Exception {
		this.mvc.perform(get("/api/users"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verifyNoInteractions(this.service);
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void listReturnsFrontContractWithoutAnyPasswordField() throws Exception {
		given(this.service.search(any(), any())).willReturn(new PageImpl<>(List.of(sample()), PageRequest.of(0, 5000), 1));

		this.mvc.perform(get("/api/users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].username").value("pierre.d"))
			.andExpect(jsonPath("$.items[0].fullName").value("Pierre Diallo"))
			.andExpect(jsonPath("$.items[0].roles[0]").value("CAISSIER"))
			.andExpect(jsonPath("$.items[0].role").value("EMPLOYEE"))
			.andExpect(jsonPath("$.items[0].isActive").value(true))
			.andExpect(jsonPath("$.items[0].active").doesNotExist())
			.andExpect(jsonPath("$.items[0].password").doesNotExist())
			.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
			.andExpect(content().string(not(containsString("bcrypt"))));
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void createReturns201WithLocation() throws Exception {
		given(this.service.create(any())).willReturn(sample());

		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "pierre.d", "fullName": "Pierre Diallo", "roles": ["CAISSIER"], "isActive": true}
				"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/users/" + ID))
			.andExpect(jsonPath("$.id").value(ID.toString()));
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void createValidatesInputServerSide() throws Exception {
		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "a b", "email": "pas-un-email", "roles": []}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
			.andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
			.andExpect(jsonPath("$.errors[?(@.field == 'roles')]").exists())
			.andExpect(jsonPath("$.errors[?(@.field == 'nameProvided')]").exists());
		verifyNoInteractions(this.service);
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void setActiveReadsTheFrontIsActiveField() throws Exception {
		given(this.service.setActive(ID, false)).willReturn(sample());

		this.mvc.perform(patch("/api/users/{id}/active", ID).contentType(MediaType.APPLICATION_JSON)
			.content("{\"isActive\": false}"))
			.andExpect(status().isOk());
		verify(this.service).setActive(ID, false);
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void deleteAnswersOkTrueLikeTheFrontExpects() throws Exception {
		this.mvc.perform(delete("/api/users/{id}", ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true));
		verify(this.service).delete(ID);
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void resetPasswordReturns204() throws Exception {
		this.mvc.perform(put("/api/users/{id}/password", ID).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": \"Optique2026\"}"))
			.andExpect(status().isNoContent());
		verify(this.service).resetPassword(eq(ID), any());
	}

	@Test
	@WithMockUser(authorities = "user:manage")
	void rejectsUnknownSortField() throws Exception {
		given(this.service.search(any(), any())).willAnswer(invocation -> {
			com.geststock.common.api.PageQuery query = invocation.getArgument(1);
			query.toPageable(UserService.SORTS);
			return new PageImpl<>(List.of());
		});

		this.mvc.perform(get("/api/users").param("sort", "passwordHash,asc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SORT_FIELD"));
	}

	private static UserResponse sample() {
		Instant now = Instant.parse("2026-10-06T10:00:00Z");
		return new UserResponse(ID, "pierre.d", "Pierre", "Diallo", "Pierre Diallo", null, null, List.of("CAISSIER"),
				"EMPLOYEE", true, true, true, null, List.of(), null, now, now);
	}

}
