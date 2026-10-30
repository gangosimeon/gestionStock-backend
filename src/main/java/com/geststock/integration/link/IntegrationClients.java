package com.geststock.integration.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/**
 * Authentification du client JeCréeMaBoutique (lot 2, étape G8 ; v14-D3) : identifiant et secret client, comparés en
 * temps constant à l'empreinte principale et à l'empreinte secondaire (rotation du secret client). Client inconnu,
 * désactivé ou secret faux : même refus, et le même travail est fait dans tous les cas (empreinte calculée, deux
 * comparaisons).
 */
@Component
public class IntegrationClients {

	private static final String CLIENT = """
			select id, client_id, secret_hash, secondary_secret_hash, status, allowed_scopes
			from platform.integration_client where client_id = :clientId""";

	private static final byte[] NO_HASH = "0".repeat(64).getBytes(StandardCharsets.US_ASCII);

	private final PlatformDb platform;

	IntegrationClients(PlatformDb platform) {
		this.platform = platform;
	}

	Optional<AuthenticatedClient> authenticate(String clientId, String secret) {
		byte[] candidate = LinkRegistry.sha256Hex(secret != null ? secret : "").getBytes(StandardCharsets.US_ASCII);
		Optional<StoredClient> stored = clientId == null || clientId.isBlank() ? Optional.empty()
				: this.platform.jdbc().sql(CLIENT).param("clientId", clientId)
					.query((rs, n) -> new StoredClient((UUID) rs.getObject("id"), rs.getString("client_id"), rs.getString("secret_hash"),
							rs.getString("secondary_secret_hash"), rs.getString("status"), strings(rs.getArray("allowed_scopes"))))
					.optional();
		byte[] primary = stored.map(c -> c.secretHash().getBytes(StandardCharsets.US_ASCII)).orElse(NO_HASH);
		byte[] secondary = stored.map(StoredClient::secondarySecretHash).map(h -> h.getBytes(StandardCharsets.US_ASCII)).orElse(NO_HASH);
		boolean primaryMatches = MessageDigest.isEqual(candidate, primary);
		boolean secondaryMatches = MessageDigest.isEqual(candidate, secondary);
		boolean secretMatches = stored.isPresent() && (primaryMatches
				|| (stored.get().secondarySecretHash() != null && secondaryMatches));
		if (!secretMatches || !"ACTIVE".equals(stored.get().status())) {
			return Optional.empty();
		}
		StoredClient client = stored.get();
		return Optional.of(new AuthenticatedClient(client.id(), client.clientId(), client.allowedScopes()));
	}

	private static Set<String> strings(Array array) throws SQLException {
		return array != null ? new HashSet<>(Arrays.asList((String[]) array.getArray())) : Set.of();
	}

	private record StoredClient(UUID id, String clientId, String secretHash, String secondarySecretHash, String status,
			Set<String> allowedScopes) {
	}

	/**
	 * Client authentifié.
	 *
	 * @param id            {@code integration_client.id}
	 * @param clientId      identifiant public (claim {@code client_id} des jetons)
	 * @param allowedScopes portées que ce client peut demander
	 */
	public record AuthenticatedClient(UUID id, String clientId, Set<String> allowedScopes) {

		public AuthenticatedClient {
			allowedScopes = Set.copyOf(allowedScopes);
		}

	}

}
