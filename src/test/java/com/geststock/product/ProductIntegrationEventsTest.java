package com.geststock.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.geststock.category.Category;
import com.geststock.common.jpa.BaseEntity;
import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.IntegrationEvents;
import com.geststock.integration.IntegrationOperation;

/**
 * Détection « rien n'a changé » des événements {@code PRODUCT} : fondée sur la sortie même du mappeur
 * ({@link ProductIntegrationEvents.Payload}), version neutralisée. Un champ exposé modifié publie ; prix
 * d'achat, prix de gros, code-barres, fournisseur et seuils de stock (hors charge) ne publient pas.
 */
class ProductIntegrationEventsTest {

	private IntegrationEvents events;

	private ProductIntegrationEvents publisher;

	private Category category;

	private Product product;

	@BeforeEach
	void setUp() throws Exception {
		this.events = mock(IntegrationEvents.class);
		this.publisher = new ProductIntegrationEvents(this.events);
		this.category = new Category("Montures", null);
		set(this.category, "id", UUID.randomUUID());
		this.product = new Product("MON-1", "Monture", this.category);
		set(this.product, "id", UUID.randomUUID());
		this.product.reprice(new BigDecimal("15000"), new BigDecimal("35000"), new BigDecimal("28000"));
	}

	@Test
	void aChangedExposedFieldPublishes() throws Exception {
		ProductIntegrationEvents.Payload before = ProductIntegrationEvents.Payload.of(this.product).withoutVersion();
		this.product.reprice(new BigDecimal("15000"), new BigDecimal("36000"), new BigDecimal("28000"));
		set(this.product, "version", 1L);

		this.publisher.publishIfChanged(before, this.product);

		verify(this.events).publish(eq(IntegrationEventType.PRODUCT), eq(IntegrationOperation.UPSERT), eq(this.product),
				argThat(payload -> payload instanceof ProductIntegrationEvents.Payload p && p.referencePrice().equals("36000.00")
						&& p.version() == 1L));
	}

	@Test
	void aChangeOutsideThePayloadPublishesNothing() throws Exception {
		ProductIntegrationEvents.Payload before = ProductIntegrationEvents.Payload.of(this.product).withoutVersion();
		this.product.reprice(new BigDecimal("16000"), new BigDecimal("35000.00"), new BigDecimal("30000"));
		this.product.identify("MON-1", "99887766554433");
		this.product.classify(this.category, null, UUID.randomUUID());
		this.product.setStockThresholds(7, 20);
		set(this.product, "version", 3L);

		this.publisher.publishIfChanged(before, this.product);

		verify(this.events, never()).publish(any(), any(), any(), any());
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = BaseEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

}
