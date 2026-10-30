package com.geststock.integration.link;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Corps d'une réponse des routes du client : sérialisé tel quel en JSON, masqué dans tout journal. Spring MVC
 * journalise en DEBUG le corps écrit ({@code "Writing [...]"}, via {@code toString}) : sans ce masque, un
 * {@code request_uri}, un {@code access_token} ou un {@code link_secret} pourrait y apparaître.
 *
 * @param values membres du corps JSON
 */
record OAuthResponseBody(Map<String, Object> values) {

	@JsonValue
	Map<String, Object> json() {
		return this.values;
	}

	@Override
	public String toString() {
		return "OAuthResponseBody[***]";
	}

}
