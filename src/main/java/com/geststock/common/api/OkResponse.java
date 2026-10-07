package com.geststock.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Réponse {@code {"ok": true}} attendue par le front après une suppression (contrat historique). */
@Schema(name = "OkResponse")
public record OkResponse(boolean ok) {

	private static final OkResponse SUCCESS = new OkResponse(true);

	public static OkResponse success() {
		return SUCCESS;
	}

}
