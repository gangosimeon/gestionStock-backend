package com.geststock.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Description d'une opération à tracer, construite par le module concerné. Elle ne contient
 * <b>jamais</b> l'auteur : {@link AuditService} le relève lui-même dans le contexte de sécurité.
 *
 * <pre>{@code
 * audit.record(AuditEvent.of("UPDATE", "PRODUCT", product.getId(), product.getSku())
 *     .before(old).after(updated).details("Prix de vente modifié"));
 * }</pre>
 *
 * Avec {@link #before(Object)} et {@link #after(Object)}, les différences champ par champ sont
 * calculées automatiquement (les champs sensibles — mots de passe, jetons — sont masqués).
 */
public final class AuditEvent {

	private final String action;

	private final String entityType;

	private final String entityId;

	private final String entityLabel;

	private AuditModule module;

	private String details;

	private Object before;

	private Object after;

	private UUID warehouseId;

	private final Map<String, Object> meta = new LinkedHashMap<>();

	private AuditEvent(String action, String entityType, String entityId, String entityLabel) {
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.entityLabel = entityLabel;
		this.module = AuditModule.of(entityType);
	}

	/**
	 * @param action     verbe en majuscules ({@code CREATE}, {@code UPDATE}, {@code CANCEL}…)
	 * @param entityType type d'objet déclaré dans {@link AuditModule}
	 * @param entityId   identifiant de l'objet (ou {@code null})
	 * @param label      libellé lisible : numéro de pièce, code, nom…
	 */
	public static AuditEvent of(String action, String entityType, Object entityId, String label) {
		return new AuditEvent(action, entityType, entityId != null ? entityId.toString() : null, label);
	}

	/** Module explicite, quand il diffère de celui du type d'entité (connexion : {@code AUTH}). */
	public AuditEvent module(AuditModule value) {
		this.module = value;
		return this;
	}

	public AuditEvent details(String value) {
		this.details = value;
		return this;
	}

	/** État avant l'opération (modification, suppression). */
	public AuditEvent before(Object value) {
		this.before = value;
		return this;
	}

	/** État après l'opération (création, modification). */
	public AuditEvent after(Object value) {
		this.after = value;
		return this;
	}

	public AuditEvent warehouse(UUID value) {
		this.warehouseId = value;
		return this;
	}

	public AuditEvent meta(String key, Object value) {
		if (value != null) {
			this.meta.put(key, value);
		}
		return this;
	}

	String action() {
		return this.action;
	}

	String entityType() {
		return this.entityType;
	}

	String entityId() {
		return this.entityId;
	}

	String entityLabel() {
		return this.entityLabel;
	}

	AuditModule module() {
		return this.module;
	}

	String details() {
		return this.details;
	}

	Object beforeState() {
		return this.before;
	}

	Object afterState() {
		return this.after;
	}

	UUID warehouseId() {
		return this.warehouseId;
	}

	Map<String, Object> meta() {
		return this.meta;
	}

}
