package com.geststock.tenancy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** URL JDBC PostgreSQL : nom de base extrait ou remplacé (hôte, port et options conservés). */
public final class JdbcUrls {

	private static final Pattern POSTGRES = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^?]*)(\\?.*)?$");

	/** Nom de base PostgreSQL accepté pour une base dédiée : généré par le backend, jamais saisi. */
	public static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

	private JdbcUrls() {
	}

	public static String databaseName(String url) {
		Matcher m = matcher(url);
		return m.group(2).toLowerCase(Locale.ROOT);
	}

	/** Même serveur et mêmes options, autre base. */
	public static String withDatabase(String url, String database) {
		requireSafeIdentifier(database);
		Matcher m = matcher(url);
		return m.group(1) + database + (m.group(3) != null ? m.group(3) : "");
	}

	public static String requireSafeIdentifier(String identifier) {
		if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
			throw new IllegalArgumentException("Identifiant PostgreSQL refusé : " + identifier);
		}
		return identifier;
	}

	private static Matcher matcher(String url) {
		Matcher m = url != null ? POSTGRES.matcher(url) : null;
		if (m == null || !m.matches()) {
			throw new IllegalArgumentException("URL JDBC PostgreSQL attendue (jdbc:postgresql://hôte:port/base)");
		}
		return m;
	}

}
