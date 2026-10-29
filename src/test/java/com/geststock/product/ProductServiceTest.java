package com.geststock.product;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.geststock.audit.AuditService;
import com.geststock.brand.BrandService;
import com.geststock.category.Category;
import com.geststock.category.CategoryService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.jpa.BaseEntity;
import com.geststock.exception.ApiException;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.product.dto.ProductFilter;
import com.geststock.product.dto.ProductRequest;
import com.geststock.product.dto.ProductResponse;
import com.geststock.supplier.SupplierService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final UUID WAREHOUSE = UUID.randomUUID();

	@Mock
	private ProductRepository repository;

	@Mock
	private CategoryService categories;

	@Mock
	private BrandService brands;

	@Mock
	private SupplierService suppliers;

	@Mock
	private ObjectProvider<ProductStockReader> stockReaderProvider;

	@Mock
	private ProductStockReader stockReader;

	@Mock
	private EntityManager entityManager;

	private ProductService service;

	private Category frames;

	@BeforeEach
	void setUp() {
		this.service = new ProductService(this.repository, this.categories, this.brands, this.suppliers,
				this.stockReaderProvider, this.entityManager, org.mockito.Mockito.mock(AuditService.class),
				org.mockito.Mockito.mock(ProductImageService.class), org.mockito.Mockito.mock(ProductIntegrationEvents.class));
		this.frames = withId(new Category("Montures", null));
	}

	@Test
	void displayedStockComesFromTheStockModuleForTheCurrentWarehouse() {
		Product product = withId(new Product("MON-1", "Monture", this.frames));
		given(this.repository.findAll(any(Specification.class), any(Pageable.class))).willReturn(new PageImpl<>(List.of(product)));
		given(this.stockReaderProvider.getIfAvailable()).willReturn(this.stockReader);
		given(this.stockReader.quantities(List.of(product.getId()), WAREHOUSE)).willReturn(Map.of(product.getId(), 7));

		ProductResponse response = this.service
			.search(new ProductFilter(null, null, null, null, null), new PageQuery(null, null, null), WAREHOUSE)
			.getContent().get(0);

		assertThat(response.stockQuantity()).isEqualTo(7);
	}

	@Test
	void stockIsZeroWhileNoStockModuleIsAvailable() {
		Product product = withId(new Product("MON-1", "Monture", this.frames));
		given(this.repository.findById(product.getId())).willReturn(Optional.of(product));
		given(this.stockReaderProvider.getIfAvailable()).willReturn(null);

		assertThat(this.service.get(product.getId(), WAREHOUSE).stockQuantity()).isZero();
	}

	@Test
	void rejectsDuplicateSkuBeforeTouchingTheDatabase() {
		given(this.repository.existsBySkuIgnoringCase(eq("MON-1"), any())).willReturn(true);

		assertThatThrownBy(() -> this.service.create(request("MON-1", null), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SKU_ALREADY_USED);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsDuplicateBarcode() {
		given(this.repository.existsByBarcode(eq("3600522119874"), any())).willReturn(true);

		assertThatThrownBy(() -> this.service.create(request("MON-2", "3600522119874"), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.BARCODE_ALREADY_USED);
	}

	@Test
	void keepsADeactivatedCategoryWhenItDoesNotChange() {
		Product product = withId(new Product("MON-1", "Monture", this.frames));
		this.frames.deactivate();
		given(this.repository.findById(product.getId())).willReturn(Optional.of(product));
		given(this.repository.saveAndFlush(product)).willReturn(product);

		this.service.update(product.getId(), request("MON-1", null), WAREHOUSE);

		verify(this.categories, never()).getForAssignment(any(), eq(true));
		assertThat(product.getCategory()).isSameAs(this.frames);
	}

	@Test
	void rejectsAnInactiveSupplierForANewProduct() {
		UUID supplier = UUID.randomUUID();
		given(this.categories.getForAssignment(this.frames.getId(), true)).willReturn(this.frames);
		given(this.suppliers.getForAssignment(supplier, true))
			.willThrow(new BusinessRuleException(ErrorCode.SUPPLIER_INACTIVE, "désactivé"));

		assertThatThrownBy(() -> this.service.create(request("MON-3", null, supplier), WAREHOUSE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void keepsTheCurrentSupplierWithoutCheckingItAgain() {
		UUID supplier = UUID.randomUUID();
		Product product = withId(new Product("MON-1", "Monture", this.frames));
		product.classify(this.frames, null, supplier);
		given(this.repository.findById(product.getId())).willReturn(Optional.of(product));
		given(this.repository.saveAndFlush(product)).willReturn(product);

		this.service.update(product.getId(), request("MON-1", null, supplier), WAREHOUSE);

		verify(this.suppliers, never()).getForAssignment(any(), eq(true));
		assertThat(product.getDefaultSupplierId()).isEqualTo(supplier);
	}

	private ProductRequest request(String sku, String barcode) {
		return request(sku, barcode, null);
	}

	private ProductRequest request(String sku, String barcode, UUID supplierId) {
		return new ProductRequest(sku, barcode, "Monture", null, this.frames.getId(), null, supplierId, null,
				new BigDecimal("15000"), new BigDecimal("35000"), new BigDecimal("28000"), 3, null);
	}

	private static <T extends BaseEntity> T withId(T entity) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, UUID.randomUUID());
			return entity;
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
