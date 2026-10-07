package com.geststock.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Entrée du journal d'audit métier (table {@code audit_log}, migration V006).
 *
 * <p>Immuable : {@link Immutable} côté Hibernate et trigger {@code fn_prevent_modification}
 * côté base. Pas de {@code version} ni de colonnes {@code updated_*}. Les champs JSON
 * ({@code changes}, {@code beforeState}, {@code afterState}, {@code meta}) contiennent du JSON
 * déjà sérialisé par le service d'audit.
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLog {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "user_id", updatable = false)
	private UUID userId;

	@Column(name = "username", length = 50, updatable = false)
	private String username;

	@Column(name = "user_role", length = 50, updatable = false)
	private String userRole;

	@Column(name = "action", nullable = false, length = 30, updatable = false)
	private String action;

	@Column(name = "entity_type", nullable = false, length = 50, updatable = false)
	private String entityType;

	@Column(name = "entity_id", length = 100, updatable = false)
	private String entityId;

	@Column(name = "entity_label", updatable = false)
	private String entityLabel;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10, updatable = false)
	private AuditStatus status;

	@Column(name = "details", updatable = false)
	private String details;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "changes", updatable = false)
	private String changes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_state", updatable = false)
	private String beforeState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_state", updatable = false)
	private String afterState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "meta", updatable = false)
	private String meta;

	@Column(name = "ip_address", length = 45, updatable = false)
	private String ipAddress;

	@Column(name = "user_agent", updatable = false)
	private String userAgent;

	@Column(name = "warehouse_id", updatable = false)
	private UUID warehouseId;

	@Column(name = "trace_id", length = 64, updatable = false)
	private String traceId;

	protected AuditLog() {
		// JPA
	}

	private AuditLog(Builder builder) {
		this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt");
		this.action = Objects.requireNonNull(builder.action, "action");
		this.entityType = Objects.requireNonNull(builder.entityType, "entityType");
		this.status = Objects.requireNonNull(builder.status, "status");
		this.userId = builder.userId;
		this.username = builder.username;
		this.userRole = builder.userRole;
		this.entityId = builder.entityId;
		this.entityLabel = builder.entityLabel;
		this.details = builder.details;
		this.changes = builder.changes;
		this.beforeState = builder.beforeState;
		this.afterState = builder.afterState;
		this.meta = builder.meta;
		this.ipAddress = builder.ipAddress;
		this.userAgent = builder.userAgent;
		this.warehouseId = builder.warehouseId;
		this.traceId = builder.traceId;
	}

	public static Builder builder(Instant createdAt, String action, String entityType, AuditStatus status) {
		return new Builder(createdAt, action, entityType, status);
	}

	public UUID getId() {
		return this.id;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getUsername() {
		return this.username;
	}

	public String getUserRole() {
		return this.userRole;
	}

	public String getAction() {
		return this.action;
	}

	public String getEntityType() {
		return this.entityType;
	}

	public String getEntityId() {
		return this.entityId;
	}

	public String getEntityLabel() {
		return this.entityLabel;
	}

	public AuditStatus getStatus() {
		return this.status;
	}

	public String getDetails() {
		return this.details;
	}

	public String getChanges() {
		return this.changes;
	}

	public String getBeforeState() {
		return this.beforeState;
	}

	public String getAfterState() {
		return this.afterState;
	}

	public String getMeta() {
		return this.meta;
	}

	public String getIpAddress() {
		return this.ipAddress;
	}

	public String getUserAgent() {
		return this.userAgent;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public String getTraceId() {
		return this.traceId;
	}

	/** Construction d'une entrée : tous les champs sont figés à la création. */
	public static final class Builder {

		private final Instant createdAt;

		private final String action;

		private final String entityType;

		private final AuditStatus status;

		private UUID userId;

		private String username;

		private String userRole;

		private String entityId;

		private String entityLabel;

		private String details;

		private String changes;

		private String beforeState;

		private String afterState;

		private String meta;

		private String ipAddress;

		private String userAgent;

		private UUID warehouseId;

		private String traceId;

		private Builder(Instant createdAt, String action, String entityType, AuditStatus status) {
			this.createdAt = createdAt;
			this.action = action;
			this.entityType = entityType;
			this.status = status;
		}

		public Builder user(UUID id, String name, String role) {
			this.userId = id;
			this.username = name;
			this.userRole = role;
			return this;
		}

		public Builder entity(String id, String label) {
			this.entityId = id;
			this.entityLabel = label;
			return this;
		}

		public Builder details(String value) {
			this.details = value;
			return this;
		}

		public Builder changes(String json) {
			this.changes = json;
			return this;
		}

		public Builder states(String beforeJson, String afterJson) {
			this.beforeState = beforeJson;
			this.afterState = afterJson;
			return this;
		}

		public Builder meta(String json) {
			this.meta = json;
			return this;
		}

		public Builder client(String ip, String agent) {
			this.ipAddress = ip;
			this.userAgent = agent;
			return this;
		}

		public Builder warehouseId(UUID value) {
			this.warehouseId = value;
			return this;
		}

		public Builder traceId(String value) {
			this.traceId = value;
			return this;
		}

		public AuditLog build() {
			return new AuditLog(this);
		}

	}

}
