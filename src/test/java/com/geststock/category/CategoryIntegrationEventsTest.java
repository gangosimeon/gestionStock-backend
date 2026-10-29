package com.geststock.category;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.common.jpa.BaseEntity;
import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.IntegrationEvents;
import com.geststock.integration.IntegrationOperation;

/**
 * Détection « rien n'a changé » des événements {@code CATEGORY} : fondée sur la sortie même du mappeur
 * ({@link CategoryIntegrationEvents.Payload}), version neutralisée. Un champ exposé modifié publie ; une
 * modification hors charge (version, horodatage d'audit) ne publie pas.
 */
class CategoryIntegrationEventsTest {

	private IntegrationEvents events;

	private CategoryIntegrationEvents publisher;

	private Category category;

	@BeforeEach
	void setUp() throws Exception {
		this.events = mock(IntegrationEvents.class);
		this.publisher = new CategoryIntegrationEvents(this.events);
		this.category = new Category("Montures", "Optique");
		set(BaseEntity.class, this.category, "id", UUID.randomUUID());
	}

	@Test
	void aChangedExposedFieldPublishes() throws Exception {
		CategoryIntegrationEvents.Payload before = CategoryIntegrationEvents.Payload.of(this.category).withoutVersion();
		this.category.update("Montures solaires", "Optique");
		set(BaseEntity.class, this.category, "version", 1L);

		this.publisher.publishIfChanged(before, this.category);

		verify(this.events).publish(eq(IntegrationEventType.CATEGORY), eq(IntegrationOperation.UPSERT), eq(this.category),
				eq(new CategoryIntegrationEvents.Payload(this.category.getId(), "Montures solaires", "Optique", true, 1L)));
	}

	@Test
	void aChangeOutsideThePayloadPublishesNothing() throws Exception {
		CategoryIntegrationEvents.Payload before = CategoryIntegrationEvents.Payload.of(this.category).withoutVersion();
		set(BaseEntity.class, this.category, "version", 4L);
		set(AuditableEntity.class, this.category, "updatedAt", Instant.now());

		this.publisher.publishIfChanged(before, this.category);

		verify(this.events, never()).publish(any(), any(), any(), any());
	}

	private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

}
