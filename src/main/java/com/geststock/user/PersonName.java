package com.geststock.user;

import org.springframework.util.StringUtils;

import com.geststock.exception.InvalidRequestException;
import com.geststock.user.dto.UserConstraints;

/**
 * Prénom (facultatif) et nom (obligatoire), normalisés.
 *
 * <p>Si seuls {@code fullName} est fourni (contrat du front actuel), il est découpé au
 * premier espace : « Pierre Diallo » → Pierre / Diallo ; « Admin » → nom seul.
 */
record PersonName(String firstName, String lastName) {

	static PersonName of(String firstName, String lastName, String fullName) {
		if (StringUtils.hasText(lastName)) {
			return checked(normalize(firstName), normalize(lastName));
		}
		String full = normalize(fullName);
		if (full == null) {
			throw new InvalidRequestException("Le nom est requis.");
		}
		int space = full.indexOf(' ');
		return space < 0 ? checked(null, full) : checked(full.substring(0, space), full.substring(space + 1).trim());
	}

	private static PersonName checked(String first, String last) {
		if ((first != null && first.length() > UserConstraints.NAME_MAX) || last.length() > UserConstraints.NAME_MAX) {
			throw new InvalidRequestException(
					"Le prénom et le nom ne doivent pas dépasser " + UserConstraints.NAME_MAX + " caractères chacun.");
		}
		return new PersonName(first, last);
	}

	/** Supprime les espaces superflus ; chaîne vide → {@code null}. */
	private static String normalize(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.trim().replaceAll("\\s+", " ");
	}

}
