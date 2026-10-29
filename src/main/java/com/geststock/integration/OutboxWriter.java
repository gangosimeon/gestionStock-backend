package com.geststock.integration;

import java.util.OptionalLong;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seule classe qui écrit dans {@code integration_outbox} et dans {@code product_image_generation} (vérifié par
 * {@code IntegrationArchitectureTest}). Visible du seul package : tout appel passe par {@link IntegrationEvents}. Utilise la {@code DataSource}
 * de l'application, donc la connexion et la transaction en cours (JPA) de l'entreprise courante. Le
 * {@code seq} reste nul : il est attribué à la lecture du flux.
 */
@Component
class OutboxWriter {

	/**
	 * Nouvelle image : crée la génération 1, ou l'incrémente si l'empreinte change. Verrouille la ligne existante
	 * même si la clause WHERE n'applique pas la mise à jour ; aucune ligne retournée : empreinte inchangée (P6).
	 */
	static final String ADVANCE_ON_UPLOAD = """
			insert into product_image_generation (product_id, generation, sha256, updated_at)
			values (:productId, 1, :sha256, now())
			on conflict (product_id) do update
			set generation = product_image_generation.generation + 1, sha256 = excluded.sha256, updated_at = now()
			where product_image_generation.sha256 is distinct from excluded.sha256
			returning generation""";

	/**
	 * Image retirée : incrémente seulement une génération qui porte une image ; ne crée jamais de ligne (P6).
	 */
	static final String ADVANCE_ON_DELETE = """
			update product_image_generation
			set generation = generation + 1, sha256 = null, updated_at = now()
			where product_id = :productId and sha256 is not null
			returning generation""";

	private final JdbcClient jdbc;

	OutboxWriter(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	void insert(UUID id, IntegrationEventType type, IntegrationOperation operation, UUID objectId, long objectVersion,
			String payloadJson) {
		this.jdbc.sql("""
				insert into integration_outbox (id, type, operation, object_id, object_version, payload)
				values (:id, :type, :operation, :objectId, :objectVersion, cast(:payload as jsonb))""")
			.param("id", id)
			.param("type", type.name())
			.param("operation", operation.name())
			.param("objectId", objectId)
			.param("objectVersion", objectVersion)
			.param("payload", payloadJson)
			.update();
	}

	/**
	 * Avance la génération de l'image d'un produit (P7) ; vide si rien ne change (P6).
	 *
	 * @param sha256 empreinte de la nouvelle image ; {@code null} si l'image est retirée
	 */
	OptionalLong advanceProductImageGeneration(UUID productId, String sha256) {
		JdbcClient.StatementSpec statement = sha256 != null
				? this.jdbc.sql(ADVANCE_ON_UPLOAD).param("productId", productId).param("sha256", sha256)
				: this.jdbc.sql(ADVANCE_ON_DELETE).param("productId", productId);
		return statement.query(Long.class).optional().map(OptionalLong::of).orElseGet(OptionalLong::empty);
	}

}
