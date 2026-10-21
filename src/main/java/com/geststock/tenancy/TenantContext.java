package com.geststock.tenancy;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Entreprise courante du thread. Fixée par le serveur uniquement : claim signé {@code tid} d'un jeton
 * d'accès, ou code entreprise d'une requête d'authentification (la base y est seulement désignée ;
 * l'identifiant et le secret doivent y être valides).
 *
 * <p>Vidée à la fin de chaque requête par {@link TenantContextFilter}. Hors requête (migrations,
 * provisionnement), utiliser {@link #callAs}, qui restaure le contexte précédent.
 */
public final class TenantContext {

	private static final ThreadLocal<TenantRef> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static Optional<TenantRef> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	/** Entreprise courante ; erreur si aucune n'est fixée. */
	public static TenantRef require() {
		TenantRef tenant = CURRENT.get();
		if (tenant == null) {
			throw new TenantContextMissingException();
		}
		return tenant;
	}

	/**
	 * Fixe l'entreprise de la requête. Refuse de remplacer une autre entreprise déjà fixée : une
	 * requête ne change jamais d'entreprise en cours de route.
	 */
	public static void set(TenantRef tenant) {
		TenantRef existing = CURRENT.get();
		if (existing != null && !existing.companyId().equals(tenant.companyId())) {
			throw new IllegalStateException("Entreprise déjà fixée pour ce traitement : " + existing.code());
		}
		CURRENT.set(tenant);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** Exécute une action pour une entreprise donnée, puis restaure le contexte précédent. */
	public static <T> T callAs(TenantRef tenant, Supplier<T> action) {
		TenantRef previous = CURRENT.get();
		CURRENT.set(tenant);
		try {
			return action.get();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}

	public static void runAs(TenantRef tenant, Runnable action) {
		callAs(tenant, () -> {
			action.run();
			return null;
		});
	}

}
