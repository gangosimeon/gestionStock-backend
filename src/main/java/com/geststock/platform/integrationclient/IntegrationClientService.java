package com.geststock.platform.integrationclient;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.integration.link.LinkRegistry;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ClientListItem;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ClientResponse;
import com.geststock.platform.integrationclient.IntegrationClientDtos.CreateClientRequest;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ResetSecretResponse;

/**
 * Gestion des clients OAuth d'intégration (Super Admin, lot 2 ; G8) :
 * création, consultation, réinitialisation du secret, désactivation.
 */
@Service
class IntegrationClientService {

	private static final Set<String> ALL_SCOPES = Set.of(
			"catalog:read", "catalog:match", "price:propose",
			"stock:read", "orders:read", "orders:write");

	private static final String SELECT = """
			select id, client_id, name, allowed_scopes, status, created_at, updated_at
			from platform.integration_client""";

	private final PlatformDb platform;

	IntegrationClientService(PlatformDb platform) {
		this.platform = platform;
	}

	List<ClientListItem> list() {
		return this.platform.jdbc().sql(SELECT + " order by created_at desc")
				.query((rs, n) -> new ClientListItem(
						(UUID) rs.getObject("id"),
						rs.getString("client_id"),
						rs.getString("name"),
						rs.getString("status"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	ClientResponse get(UUID id) {
		return this.platform.jdbc().sql(SELECT + " where id = :id").param("id", id)
				.query((rs, n) -> new ClientResponse(
						(UUID) rs.getObject("id"),
						rs.getString("client_id"),
						rs.getString("name"),
						rs.getString("status"),
						rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("updated_at").toInstant()))
				.optional()
				.orElseThrow(() -> new NotFoundException(ErrorCode.INTEGRATION_CLIENT_NOT_FOUND,
						ErrorCode.INTEGRATION_CLIENT_NOT_FOUND.defaultMessage()));
	}

	/** Crée un client et retourne son secret en clair (une seule fois). */
	CreateResult create(CreateClientRequest request, UUID createdBy) {
		String secret = generateSecret();
		String secretHash = LinkRegistry.sha256Hex(secret);
		Set<String> scopes = request.allowedScopes() == null || request.allowedScopes().isEmpty()
				? ALL_SCOPES : request.allowedScopes();
		UUID id = UUID.randomUUID();
		try {
			this.platform.jdbc().sql("""
					insert into platform.integration_client
					    (id, client_id, name, secret_hash, allowed_scopes, status, created_by)
					values
					    (:id, :clientId, :name, :secretHash, :scopes::text[], 'ACTIVE', :createdBy)""")
					.param("id", id)
					.param("clientId", request.clientId())
					.param("name", request.name())
					.param("secretHash", secretHash)
					.param("scopes", scopes.stream().sorted().toList().toArray(String[]::new))
					.param("createdBy", createdBy)
					.update();
		} catch (DuplicateKeyException e) {
			throw new ConflictException(ErrorCode.INTEGRATION_CLIENT_ID_ALREADY_USED,
					ErrorCode.INTEGRATION_CLIENT_ID_ALREADY_USED.defaultMessage());
		}
		for (String uri : request.redirectUris()) {
			this.platform.jdbc().sql("""
					insert into platform.integration_client_redirect_uri (id, client_id, redirect_uri)
					values (:id, :clientId, :uri)""")
					.param("id", UUID.randomUUID())
					.param("clientId", id)
					.param("uri", uri)
					.update();
		}
		return new CreateResult(get(id), secret);
	}

	/** Génère un nouveau secret principal et retourne-le en clair. L'ancien est effacé. */
	String resetSecret(UUID id) {
		get(id); // vérifie l'existence
		String secret = generateSecret();
		this.platform.jdbc().sql("""
				update platform.integration_client
				set secret_hash = :hash, secondary_secret_hash = null, updated_at = now()
				where id = :id""")
				.param("hash", LinkRegistry.sha256Hex(secret))
				.param("id", id)
				.update();
		return secret;
	}

	void disable(UUID id) {
		get(id);
		this.platform.jdbc().sql("""
				update platform.integration_client
				set status = 'DISABLED', disabled_at = now(), updated_at = now()
				where id = :id and status = 'ACTIVE'""")
				.param("id", id)
				.update();
	}

	void enable(UUID id) {
		get(id);
		this.platform.jdbc().sql("""
				update platform.integration_client
				set status = 'ACTIVE', disabled_at = null, updated_at = now()
				where id = :id and status = 'DISABLED'""")
				.param("id", id)
				.update();
	}

	private static String generateSecret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	record CreateResult(ClientResponse client, String secret) {
	}

}
