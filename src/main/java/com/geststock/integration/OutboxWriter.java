package com.geststock.integration;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seule classe qui écrit dans {@code integration_outbox} (vérifié par {@code IntegrationArchitectureTest}).
 * Visible du seul package : tout appel passe par {@link IntegrationEvents}. Utilise la {@code DataSource}
 * de l'application, donc la connexion et la transaction en cours (JPA) de l'entreprise courante. Le
 * {@code seq} reste nul : il est attribué à la lecture du flux.
 */
@Component
class OutboxWriter {

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

}
