package com.geststock.security;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantDirectory.TenantStatus;
import com.geststock.tenancy.TenantRef;

/**
 * Entreprise d'une requête d'authentification (connexion, renouvellement, activation) : le code
 * entreprise désigne seulement la base où chercher l'identifiant ou le jeton, qui doivent y être
 * valides. Il ne donne aucun droit.
 *
 * <p>Code inconnu : même réponse qu'un mauvais mot de passe (pas d'énumération). Entreprise suspendue :
 * 403 {@code COMPANY_SUSPENDED} (écran explicatif côté front).
 */
@Component
public class AuthCompanyResolver {

	private final TenantDirectory directory;

	private final DefaultTenant defaultTenant;

	AuthCompanyResolver(TenantDirectory directory, DefaultTenant defaultTenant) {
		this.directory = directory;
		this.defaultTenant = defaultTenant;
	}

	/** Fixe l'entreprise de la requête (connexion) ; erreur si elle est inconnue ou inaccessible. */
	public TenantRef enter(String companyCode) {
		TenantStatus status = find(companyCode).orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS));
		requireActive(status);
		TenantContext.set(status.ref());
		return status.ref();
	}

	/** Variante silencieuse (déconnexion) : rien n'est fixé si l'entreprise est inconnue ou inaccessible. */
	public Optional<TenantRef> tryEnter(String companyCode) {
		Optional<TenantStatus> status;
		try {
			status = find(companyCode).filter(TenantStatus::isActive);
		}
		catch (InvalidRequestException ex) {
			return Optional.empty();
		}
		status.ifPresent(s -> TenantContext.set(s.ref()));
		return status.map(TenantStatus::ref);
	}

	static void requireActive(TenantStatus status) {
		if (status.isSuspended()) {
			throw new ForbiddenOperationException(ErrorCode.COMPANY_SUSPENDED, ErrorCode.COMPANY_SUSPENDED.defaultMessage());
		}
		if (!status.isActive()) {
			throw new ForbiddenOperationException(ErrorCode.COMPANY_UNAVAILABLE, ErrorCode.COMPANY_UNAVAILABLE.defaultMessage());
		}
	}

	private Optional<TenantStatus> find(String companyCode) {
		if (companyCode == null || companyCode.isBlank()) {
			TenantRef fallback = this.defaultTenant.get()
				.orElseThrow(() -> new InvalidRequestException(ErrorCode.COMPANY_REQUIRED, ErrorCode.COMPANY_REQUIRED.defaultMessage()));
			return this.directory.findById(fallback.companyId());
		}
		return this.directory.findByCode(companyCode);
	}

}
