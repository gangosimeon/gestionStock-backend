package com.geststock.integration;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.geststock.common.jpa.BaseEntity;

import tools.jackson.databind.json.JsonMapper;

/**
 * Point d'entrée <b>unique</b> de publication des événements d'intégration (exigence P0-4) : aucun
 * service métier n'écrit dans {@code integration_outbox} autrement que par cette classe.
 *
 * <p>Règles :
 * <ul>
 * <li>appel <b>dans la transaction métier</b> de l'appelant ({@link Propagation#MANDATORY}) : l'événement
 * est validé ou annulé avec le changement qu'il décrit ; un appel hors transaction échoue ;</li>
 * <li>règle R1 : flush de l'{@link EntityManager} (la ligne métier est écrite et verrouillée), puis lecture
 * du {@code @Version} de l'entité, puis insertion. La version n'est jamais fournie par l'appelant ;</li>
 * <li>la charge est sérialisée par le {@link JsonMapper} de l'application et bornée
 * ({@code geststock.integration.publish.max-payload-size}) ; elle reste minimale et ne contient
 * <b>jamais de donnée personnelle</b> (nom, téléphone, adresse d'un client…) ;</li>
 * <li>interrupteur {@code geststock.integration.publish.enabled} coupé : rien n'est écrit.</li>
 * </ul>
 */
@Service
public class IntegrationEvents {

	/**
	 * Version écrite pour un événement mince ({@link IntegrationEventType#isThin()}) : aucune. Sa version
	 * réelle est son {@code seq}, connu seulement au moment de servir la page du flux, où son contenu est
	 * recalculé depuis les données validées (décision P3). Seul endroit où cette convention est écrite.
	 */
	static final long THIN_EVENT_VERSION = 0L;

	private final IntegrationProperties properties;

	private final OutboxWriter writer;

	private final JsonMapper jsonMapper;

	private final EntityManager entityManager;

	IntegrationEvents(IntegrationProperties properties, OutboxWriter writer, JsonMapper jsonMapper,
			EntityManager entityManager) {
		this.properties = properties;
		this.writer = writer;
		this.jsonMapper = jsonMapper;
		this.entityManager = entityManager;
	}

	/**
	 * Publie le changement d'une entité versionnée (catégorie, produit…).
	 *
	 * @param type      type d'événement non mince
	 * @param operation opération
	 * @param entity    entité gérée par la transaction en cours ; son identifiant et son {@code @Version}
	 *                  (lu après flush) deviennent {@code object_id} et {@code object_version}
	 * @param payload   charge minimale, sans donnée personnelle ; {@code null} si aucune
	 * @throws IllegalArgumentException            type mince, ou entité non gérée par la transaction
	 * @throws IntegrationPayloadTooLargeException charge trop lourde (rien n'est écrit)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(IntegrationEventType type, IntegrationOperation operation, BaseEntity entity, Object payload) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(entity, "entity");
		if (type.isThin()) {
			throw new IllegalArgumentException("Événement mince " + type + " : utiliser publish(type, objectId)");
		}
		if (!enabled()) {
			return;
		}
		String json = serialize(type, payload);
		if (!this.entityManager.contains(entity)) {
			throw new IllegalArgumentException("Entité non gérée par la transaction en cours : " + entity);
		}
		// R1 : la ligne métier est écrite (et verrouillée) avant de lire sa version et d'écrire l'événement.
		this.entityManager.flush();
		this.writer.insert(newId(), type, operation, entity.getId(), entity.getVersion(), json);
	}

	/**
	 * Publie un événement mince : aucune valeur figée, aucune charge (voir {@link #THIN_EVENT_VERSION}).
	 *
	 * @param type     type d'événement mince ({@link IntegrationEventType#AVAILABILITY})
	 * @param objectId objet décrit (pour {@code AVAILABILITY} : le produit)
	 * @throws IllegalArgumentException type non mince
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(IntegrationEventType type, UUID objectId) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(objectId, "objectId");
		if (!type.isThin()) {
			throw new IllegalArgumentException("Événement " + type + " : utiliser publish(type, operation, entity, payload)");
		}
		if (!enabled()) {
			return;
		}
		this.writer.insert(newId(), type, IntegrationOperation.UPSERT, objectId, THIN_EVENT_VERSION, null);
	}

	/**
	 * Publie le changement de l'image d'un produit (P6, P7) : avance la génération durable du produit, puis écrit
	 * l'événement {@code PRODUCT_IMAGE} (version = génération), dans la transaction de l'appelant.
	 *
	 * <p>Doit être la <b>première instruction sur l'image</b> de la transaction : l'{@code INSERT … ON CONFLICT}
	 * verrouille la ligne de génération, même sans mise à jour, et sérialise ainsi les changements d'image
	 * concurrents d'un même produit. Empreinte inchangée, ou retrait d'une image absente : ni incrément, ni
	 * événement. La génération avance même interrupteur coupé : elle reste la version de l'image quand la
	 * publication est rétablie.
	 *
	 * @param productId produit
	 * @param sha256    empreinte de la nouvelle image ; {@code null} si l'image est retirée
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void publishProductImage(UUID productId, String sha256) {
		Objects.requireNonNull(productId, "productId");
		OptionalLong generation = this.writer.advanceProductImageGeneration(productId, sha256);
		if (generation.isEmpty() || !enabled()) {
			return;
		}
		long version = generation.getAsLong();
		String json = serialize(IntegrationEventType.PRODUCT_IMAGE, new ProductImageState(productId, sha256, version));
		this.writer.insert(newId(), IntegrationEventType.PRODUCT_IMAGE, IntegrationOperation.UPSERT, productId, version,
				json);
	}

	/**
	 * Publie un événement mince {@code AVAILABILITY} pour chaque produit dont le stock vient de changer, <b>au plus
	 * un par produit et par transaction</b> : un second appel dans la même transaction n'écrit rien pour un produit
	 * déjà publié. La valeur est calculée au moment de servir la page (P3).
	 *
	 * @param productIds produits touchés (doublons possibles)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void publishAvailability(Collection<UUID> productIds) {
		Objects.requireNonNull(productIds, "productIds");
		if (productIds.isEmpty() || !enabled()) {
			return;
		}
		Set<UUID> published = PublishedAvailability.ofCurrentTransaction();
		for (UUID productId : productIds) {
			if (published.add(Objects.requireNonNull(productId, "productId"))) {
				this.writer.insert(newId(), IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, productId,
						THIN_EVENT_VERSION, null);
			}
		}
	}

	/**
	 * Produits déjà publiés en {@code AVAILABILITY} dans la transaction courante. Porté par une synchronisation de
	 * transaction : propre à chaque transaction (suspendu avec elle), oublié à sa fin, validée ou annulée.
	 */
	private static final class PublishedAvailability implements TransactionSynchronization {

		private final Set<UUID> products = new HashSet<>();

		static Set<UUID> ofCurrentTransaction() {
			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				if (synchronization instanceof PublishedAvailability published) {
					return published.products;
				}
			}
			PublishedAvailability published = new PublishedAvailability();
			TransactionSynchronizationManager.registerSynchronization(published);
			return published.products;
		}

	}

	/** Charge : schéma {@code ProductImageState} du contrat ({@code imageSha256} nul après retrait). */
	private record ProductImageState(UUID productId, String imageSha256, long version) {
	}

	private boolean enabled() {
		return this.properties.publish().enabled();
	}

	private String serialize(IntegrationEventType type, Object payload) {
		if (payload == null) {
			return null;
		}
		byte[] bytes = this.jsonMapper.writeValueAsBytes(payload);
		long max = this.properties.publish().maxPayloadSize().toBytes();
		if (bytes.length > max) {
			throw new IntegrationPayloadTooLargeException(type, bytes.length, max);
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** UUID v7 : même stratégie que les identifiants des entités ({@code BaseEntity}, {@code @UuidGenerator}). */
	private static UUID newId() {
		return UuidVersion7Strategy.INSTANCE.generateUuid(null);
	}

}
