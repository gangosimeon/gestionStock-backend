package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.geststock.audit.AuditService;
import com.geststock.exception.ApiException;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.supplier.SupplierService;
import com.geststock.supplier.SupplierSnapshot;
import com.geststock.warehouse.WarehouseAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

	private static final UUID WAREHOUSE = UUID.randomUUID();

	@Mock
	private PurchaseOrderRepository orders;

	@Mock
	private GoodsReceiptRepository receipts;

	@Mock
	private SupplierService suppliers;

	@Mock
	private ProductService products;

	@Mock
	private WarehouseAccess warehouseAccess;

	private PurchaseOrderService service;

	private SupplierSnapshot essilor;

	private ProductSnapshot frame;

	private ProductSnapshot lens;

	@BeforeEach
	void setUp() {
		this.service = new PurchaseOrderService(this.orders, this.receipts, this.suppliers, this.products,
				this.warehouseAccess, org.mockito.Mockito.mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
		this.essilor = new SupplierSnapshot(UUID.randomUUID(), "FOU-ESS", "Essilor", 5, 30, true);
		this.frame = new ProductSnapshot(UUID.randomUUID(), "MON-1", "Monture", true, new BigDecimal("15000.00"),
				BigDecimal.ZERO, BigDecimal.ZERO);
		this.lens = new ProductSnapshot(UUID.randomUUID(), "LEN-1", "Lentilles", true, new BigDecimal("4500.00"),
				BigDecimal.ZERO, BigDecimal.ZERO);
	}

	@Test
	void theServerComputesCostsAndTotals() {
		given(this.suppliers.getForAssignment(this.essilor.id(), true)).willReturn(this.essilor);
		given(this.products.snapshots(anyCollection()))
			.willReturn(Map.of(this.frame.id(), this.frame, this.lens.id(), this.lens));
		given(this.orders.nextNumber()).willReturn(42L);
		given(this.orders.saveAndFlush(any(PurchaseOrder.class))).willAnswer(invocation -> invocation.getArgument(0));

		PurchaseOrderResponse created = this.service.create(new CreatePurchaseOrderRequest(this.essilor.id(), null, null,
				null, null, List.of(new CreatePurchaseOrderRequest.Line(this.frame.id(), 3, new BigDecimal("14000")),
						new CreatePurchaseOrderRequest.Line(this.lens.id(), 10, null))),
				WAREHOUSE);

		assertThat(created.number()).isEqualTo("CMD-2026-000042");
		assertThat(created.status()).isEqualTo("PENDING");
		assertThat(created.supplierName()).isEqualTo("Essilor");
		assertThat(created.warehouseId()).as("dépôt courant").isEqualTo(WAREHOUSE);
		assertThat(created.expectedDeliveryDate()).as("aujourd'hui + délai du fournisseur")
			.isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(created.lines().get(0).unitPurchasePrice()).as("coût négocié").isEqualByComparingTo("14000");
		assertThat(created.lines().get(0).lineTotal()).isEqualByComparingTo("42000");
		assertThat(created.lines().get(1).unitPurchasePrice()).as("prix d'achat de référence")
			.isEqualByComparingTo("4500");
		assertThat(created.lines().get(1).lineTotal()).isEqualByComparingTo("45000");
		assertThat(created.lines().get(1).productSku()).isEqualTo("LEN-1");
		assertThat(created.totalAmount()).isEqualByComparingTo("87000");
		assertThat(created.paidAmount()).isZero();
	}

	@Test
	void aProductCanAppearOnlyOnce() {
		given(this.suppliers.getForAssignment(this.essilor.id(), true)).willReturn(this.essilor);

		assertThatThrownBy(() -> this.service.create(new CreatePurchaseOrderRequest(this.essilor.id(), null, null, null,
				null, List.of(new CreatePurchaseOrderRequest.Line(this.frame.id(), 1, null),
						new CreatePurchaseOrderRequest.Line(this.frame.id(), 2, null))),
				WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_DUPLICATE_PRODUCT);
		verifyNothingSaved();
	}

	@Test
	void unknownOrInactiveProductsAreRefusedBeforeNumbering() {
		ProductSnapshot inactive = new ProductSnapshot(UUID.randomUUID(), "OLD-1", "Ancien", false, BigDecimal.TEN,
				BigDecimal.ZERO, BigDecimal.ZERO);
		given(this.suppliers.getForAssignment(this.essilor.id(), true)).willReturn(this.essilor);
		given(this.products.snapshots(anyCollection())).willReturn(Map.of(inactive.id(), inactive));

		assertThatThrownBy(() -> this.service.create(request(inactive.id(), 1), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PRODUCT_INACTIVE);
		assertThatThrownBy(() -> this.service.create(request(UUID.randomUUID(), 1), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
		verify(this.orders, never()).nextNumber();
		verifyNothingSaved();
	}

	@Test
	void anInactiveSupplierIsRefused() {
		given(this.suppliers.getForAssignment(this.essilor.id(), true))
			.willThrow(new BusinessRuleException(ErrorCode.SUPPLIER_INACTIVE, "désactivé"));

		assertThatThrownBy(() -> this.service.create(request(this.frame.id(), 1), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
		verifyNothingSaved();
	}

	@Test
	void anAmountBeyondTheColumnCapacityIsRefused() {
		ProductSnapshot expensive = new ProductSnapshot(UUID.randomUUID(), "LUX-1", "Luxe", true, BigDecimal.ONE,
				BigDecimal.ZERO, BigDecimal.ZERO);
		given(this.suppliers.getForAssignment(this.essilor.id(), true)).willReturn(this.essilor);
		given(this.products.snapshots(anyCollection())).willReturn(Map.of(expensive.id(), expensive));
		given(this.orders.nextNumber()).willReturn(1L);

		assertThatThrownBy(() -> this.service.create(new CreatePurchaseOrderRequest(this.essilor.id(), null, null, null,
				null, List.of(new CreatePurchaseOrderRequest.Line(expensive.id(), 100_000,
						new BigDecimal("9999999999.99")))),
				WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_AMOUNT_OUT_OF_RANGE);
		verifyNothingSaved();
	}

	@Test
	void anExplicitDeliveryWarehouseMustBeAccessible() {
		UUID depot = UUID.randomUUID();
		given(this.suppliers.getForAssignment(this.essilor.id(), true)).willReturn(this.essilor);
		given(this.warehouseAccess.requireAccessible(depot))
			.willThrow(new com.geststock.exception.ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED, "non"));

		assertThatThrownBy(() -> this.service.create(new CreatePurchaseOrderRequest(this.essilor.id(), depot, null, null,
				null, List.of(new CreatePurchaseOrderRequest.Line(this.frame.id(), 1, null))), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.WAREHOUSE_ACCESS_DENIED);
		verifyNothingSaved();
	}

	private void verifyNothingSaved() {
		verify(this.orders, never()).saveAndFlush(any());
	}

	private CreatePurchaseOrderRequest request(UUID productId, int quantity) {
		return new CreatePurchaseOrderRequest(this.essilor.id(), null, null, null, null,
				List.of(new CreatePurchaseOrderRequest.Line(productId, quantity, null)));
	}

}
