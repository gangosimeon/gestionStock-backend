package com.geststock.common.api;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Activation ou désactivation d'un référentiel ({@code PATCH .../active}). */
@Schema(name = "SetActiveRequest")
public record SetActiveRequest(@NotNull Boolean active) {
}
