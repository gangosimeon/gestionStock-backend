package com.geststock.integration;

import java.util.OptionalLong;

import org.springframework.stereotype.Component;

/** Aucune liaison avant le lot 2, donc aucun acquittement : rien n'est éligible à la purge. */
@Component
class NoAcknowledgements implements AckSource {

	@Override
	public OptionalLong minimumAcknowledged() {
		return OptionalLong.empty();
	}

}
