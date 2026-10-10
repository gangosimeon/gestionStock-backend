package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.geststock.audit.AuditService;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.product.ProductService;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.ReceivePurchaseOrderRequest;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.warehouse.WarehouseAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class GoodsReceiptServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

	private static final UUID ACTOR = UUID.randomUUID();

	private static final UUID WAREHOUSE = UUID.randomUUID();

	@Mock
	private PurchaseOrderService orders;

	@Mock
	private PurchaseOrderRepository orderRepository;

	@Mock
	private GoodsReceiptRepository receipts;

	@Mock
	private StockService stock;

	@Mock
	private ProductService products;

	@Mock
	private WarehouseAccess warehouseAccess;

	@Mock
	private EntityManager entityManager;

	private GoodsReceiptService service;

	private PurchaseOrder order;

	private UUID frames;

	private UUID lenses;

	@BeforeEach
	void setUp() {
		this.service = new GoodsReceiptService(this.orders, this.orderRepository, this.receipts, this.stock,
				this.products, this.warehouseAccess, this.entityManager, org.mockito.Mockito.mock(AuditService.class),
				Clock.fixed(NOW, ZoneOffset.UTC));
		this.order = new PurchaseOrder("CMD-2026-000007", UUID.randomUUID(), "Essilor", WAREHOUSE, null, null, null);
		this.frames = UUID.randomUUID();
		this.lenses = UUID.randomUUID();
		this.order.addLine(this.frames, "MON-1", "Monture", 10, new BigDecimal("15000"));
		this.order.addLine(this.lenses, "LEN-1", "Lentilles", 4, new BigDecimal("4500"));
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR.toString(), null, List.of()));
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anEmptyBodyReceivesTheWholeRemainderIntoTheOrderWarehouseAtTheOrderCost() {
		given(this.orders.loadForUpdate(any())).willReturn(this.order);
		given(this.receipts.nextNumber()).willReturn(12L);
		given(this.receipts.save(any(GoodsReceipt.class))).willAnswer(invocation -> invocation.getArgument(0));
		List<StockMovement> movements = List.of(movement(), movement());
		given(this.stock.applyMovements(anyList())).willReturn(movements);
		given(this.orderRepository.save(this.order)).willReturn(this.order);

		PurchaseOrderResponse response = this.service.receive(UUID.randomUUID(), empty());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<StockMovementCommand>> commands = ArgumentCaptor.forClass(List.class);
		verify(this.stock).applyMovements(commands.capture());
		assertThat(commands.getValue()).hasSize(2).allSatisfy(command -> {
			assertThat(command.type()).isEqualTo(StockMovementType.RECEIPT);
			assertThat(command.sourceType()).isEqualTo(StockSourceType.PURCHASE_ORDER);
			assertThat(command.warehouseId()).as("dépôt de la commande").isEqualTo(WAREHOUSE);
			assertThat(command.sourceReference()).isEqualTo("REC-2026-000012");
			assertThat(command.reason()).contains("CMD-2026-000007");
		});
		assertThat(commands.getValue()).extracting(StockMovementCommand::quantity).containsExactly(10, 4);
		assertThat(commands.getValue()).extracting(StockMovementCommand::unitCost)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("15000"), new BigDecimal("4500"));

		ArgumentCaptor<GoodsReceipt> receipt = ArgumentCaptor.forClass(GoodsReceipt.class);
		verify(this.receipts).save(receipt.capture());
		assertThat(receipt.getValue().getTotalQuantity()).isEqualTo(14);
		assertThat(receipt.getValue().getTotalAmount()).isEqualByComparingTo("168000");
		assertThat(receipt.getValue().getCreatedBy()).isEqualTo(ACTOR);
		assertThat(receipt.getValue().getReceivedAt()).isEqualTo(NOW);
		assertThat(receipt.getValue().getWarehouseId()).isEqualTo(WAREHOUSE);
		verify(this.entityManager, org.mockito.Mockito.times(2)).persist(any(GoodsReceiptLine.class));
		verify(this.warehouseAccess).requireAccessible(WAREHOUSE);
		verify(this.products).recordPurchasePrices(
				java.util.Map.of(this.frames, new BigDecimal("15000.00"), this.lenses, new BigDecimal("4500.00")));

		assertThat(response.status()).isEqualTo("DELIVERED");
		assertThat(response.documentStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
		assertThat(response.receivedAmount()).isEqualByComparingTo("168000");
		assertThat(response.deliveredAt()).isEqualTo(NOW);
	}

	@Test
	void aPartialReceiptOnlyMovesTheReceivedQuantities() {
		given(this.orders.loadForUpdate(any())).willReturn(this.order);
		given(this.receipts.nextNumber()).willReturn(1L);
		given(this.receipts.save(any(GoodsReceipt.class))).willAnswer(invocation -> invocation.getArgument(0));
		List<StockMovement> movements = List.of(movement());
		given(this.stock.applyMovements(anyList())).willReturn(movements);
		given(this.orderRepository.save(this.order)).willReturn(this.order);

		PurchaseOrderResponse response = this.service.receive(UUID.randomUUID(),
				request(new ReceivePurchaseOrderRequest.Line(this.frames, 3)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<StockMovementCommand>> commands = ArgumentCaptor.forClass(List.class);
		verify(this.stock).applyMovements(commands.capture());
		assertThat(commands.getValue()).singleElement().satisfies(command -> {
			assertThat(command.productId()).isEqualTo(this.frames);
			assertThat(command.quantity()).isEqualTo(3);
		});
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.documentStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
		assertThat(response.deliveredAt()).as("pas encore livrée").isNull();
		assertThat(response.lines().get(0).receivedQuantity()).isEqualTo(3);
		assertThat(response.lines().get(0).remainingQuantity()).isEqualTo(7);
		assertThat(response.lines().get(1).receivedQuantity()).isZero();
	}

	@Test
	void overReceiptIsRefusedBeforeAnythingIsWritten() {
		given(this.orders.loadForUpdate(any())).willReturn(this.order);

		assertThatThrownBy(() -> this.service.receive(UUID.randomUUID(),
				request(new ReceivePurchaseOrderRequest.Line(this.lenses, 5))))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.RECEIPT_QUANTITY_EXCEEDS_REMAINING);
		verifyNothingWritten();
	}

	@Test
	void aProductOutsideTheOrderOrListedTwiceIsRefused() {
		given(this.orders.loadForUpdate(any())).willReturn(this.order);

		assertThatThrownBy(() -> this.service.receive(UUID.randomUUID(),
				request(new ReceivePurchaseOrderRequest.Line(UUID.randomUUID(), 1))))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.RECEIPT_PRODUCT_NOT_ORDERED);
		assertThatThrownBy(() -> this.service.receive(UUID.randomUUID(),
				request(new ReceivePurchaseOrderRequest.Line(this.frames, 1),
						new ReceivePurchaseOrderRequest.Line(this.frames, 1))))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_DUPLICATE_PRODUCT);
		verifyNothingWritten();
	}

	@Test
	void aCancelledOrderCannotBeReceived() {
		this.order.cancel(NOW, ACTOR, null);
		given(this.orders.loadForUpdate(any())).willReturn(this.order);

		assertThatThrownBy(() -> this.service.receive(UUID.randomUUID(), empty()))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
		verifyNothingWritten();
		verifyNoInteractions(this.warehouseAccess);
	}

	@Test
	void anAuthenticatedUserIsMandatory() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(() -> this.service.receive(UUID.randomUUID(), empty()))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.UNAUTHENTICATED);
		verifyNoInteractions(this.orders);
		verifyNothingWritten();
	}

	private void verifyNothingWritten() {
		verify(this.receipts, never()).save(any());
		verify(this.stock, never()).applyMovements(anyList());
		verify(this.entityManager, never()).persist(any());
		verify(this.products, never()).recordPurchasePrices(any());
	}

	private static StockMovement movement() {
		StockMovement movement = mock(StockMovement.class);
		given(movement.getId()).willReturn(UUID.randomUUID());
		return movement;
	}

	private static ReceivePurchaseOrderRequest empty() {
		return new ReceivePurchaseOrderRequest(null, null, null, null);
	}

	private static ReceivePurchaseOrderRequest request(ReceivePurchaseOrderRequest.Line... lines) {
		return new ReceivePurchaseOrderRequest(null, "BL-778", null, List.of(lines));
	}

}
