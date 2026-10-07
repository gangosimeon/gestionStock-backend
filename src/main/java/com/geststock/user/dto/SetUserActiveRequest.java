package com.geststock.user.dto;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Activation ou désactivation d'un compte ({@code PATCH /api/users/{id}/active}). */
@Schema(name = "SetUserActiveRequest")
public record SetUserActiveRequest(
		@NotNull @JsonProperty("isActive") @Schema(name = "isActive") Boolean isActive) {
}
