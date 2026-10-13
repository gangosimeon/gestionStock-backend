package com.geststock.product;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.brand.Brand;
import com.geststock.brand.BrandService;
import com.geststock.category.Category;
import com.geststock.category.CategoryService;
import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.product.dto.ProductFilter;
import com.geststock.product.dto.ProductRequest;
import com.geststock.product.dto.ProductResponse;
import com.geststock.supplier.SupplierService;

/**
 * Catalogue produits (API publique du module {@code product}).
 *
 * <p>Règles :
 * <ul>
 * <li>référence (SKU) unique sans tenir compte de la casse ; code-barres unique s'il existe ;</li>
 * <li>catégorie obligatoire ; catégorie, marque et fournisseur habituel doivent être actifs pour
 * être choisis ;</li>
 * <li>désactivation plutôt que suppression (historique des ventes et mouvements préservé) ;</li>
 * <li><b>le stock n'est jamais lu ni écrit dans le produit</b> : il est affiché depuis le
 * module stock via {@link ProductStockReader}.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"sku", "sku",
			"name", "name",
			"categoryName", "category.name",
			"purchasePrice", "purchasePrice",
			"retailPrice", "retailPrice",
			"wholesalePrice", "wholesalePrice",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt"), Sort.by("name"));

	private final ProductRepository repository;

	private final CategoryService categories;

	private final BrandService brands;

	private final SupplierService suppliers;

	private final ObjectProvider<ProductStockReader> stockReader;

	private final EntityManager entityManager;

	private final AuditService audit;

	ProductService(ProductRepository repository, CategoryService categories, BrandService brands,
			SupplierService suppliers, ObjectProvider<ProductStockReader> stockReader, EntityManager entityManager,
			AuditService audit) {
		this.repository = repository;
		this.categories = categories;
		this.brands = brands;
		this.suppliers = suppliers;
		this.stockReader = stockReader;
		this.entityManager = entityManager;
		this.audit = audit;
	}

	public Page<ProductResponse> search(ProductFilter filter, PageQuery query, UUID warehouseId) {
		Page<Product> page = this.repository.findAll(ProductSpecifications.matching(filter), query.toPageable(SORTS));
		Map<UUID, Integer> stock = stockOf(page.getContent(), warehouseId);
		return page.map(product -> toResponse(product, stock));
	}

	public ProductResponse get(UUID id, UUID warehouseId) {
		Product product = load(id);
		return toResponse(product, stockOf(List.of(product), warehouseId));
	}

	@Transactional
	public ProductResponse create(ProductRequest request, UUID warehouseId) {
		String sku = request.sku().trim();
		String barcode = blankToNull(request.barcode());
		requireUniqueIdentifiers(sku, barcode, null);

		Category category = this.categories.getForAssignment(request.categoryId(), true);
		Product product = new Product(sku, request.name().trim(), category);
		apply(product, request, sku, barcode, true);
		Product saved = this.repository.saveAndFlush(product);
		ProductResponse created = toResponse(saved, stockOf(List.of(saved), warehouseId));
		this.audit.record(AuditEvent.of("CREATE", "PRODUCT", saved.getId(), label(saved))
			.details("Création du produit " + label(saved))
			.after(created));
		return created;
	}

	@Transactional
	public ProductResponse update(UUID id, ProductRequest request, UUID warehouseId) {
		Product product = load(id);
		ProductResponse before = toResponse(product, stockOf(List.of(product), warehouseId));
		String sku = request.sku().trim();
		String barcode = blankToNull(request.barcode());
		requireUniqueIdentifiers(sku, barcode, id);
		apply(product, request, sku, barcode, false);
		Product saved = this.repository.saveAndFlush(product);
		ProductResponse updated = toResponse(saved, stockOf(List.of(saved), warehouseId));
		this.audit.record(AuditEvent.of("UPDATE", "PRODUCT", id, label(saved))
			.details("Modification du produit " + label(saved))
			.before(before)
			.after(updated));
		return updated;
	}

	/**
	 * Désactive (« supprime ») ou réactive. Un produit désactivé disparaît des listes et ne
	 * pourra plus être vendu ni commandé, mais reste lisible dans l'historique.
	 */
	@Transactional
	public ProductResponse setActive(UUID id, boolean active, UUID warehouseId) {
		Product product = load(id);
		boolean wasActive = product.isActive();
		if (active) {
			product.activate();
		}
		else {
			product.deactivate();
		}
		Product saved = this.repository.saveAndFlush(product);
		if (wasActive != active) {
			this.audit.record(AuditEvent.of(active ? "ACTIVATE" : "DEACTIVATE", "PRODUCT", id, label(saved))
				.details((active ? "Réactivation" : "Désactivation") + " du produit " + label(saved))
				.before(Map.of("active", wasActive))
				.after(Map.of("active", active)));
		}
		return toResponse(saved, stockOf(List.of(saved), warehouseId));
	}

	private static String label(Product product) {
		return product.getSku() + " · " + product.getName();
	}

	/**
	 * Réception fournisseur (module achats) : le coût unitaire reçu devient le prix d'achat de
	 * référence de chaque produit (dernier prix d'achat), dans la transaction de la réception.
	 *
	 * <p>Chaque produit est relu <b>sous verrou</b> ({@code SELECT … FOR UPDATE}), dans l'ordre
	 * des identifiants : deux réceptions simultanées d'un même produit se sérialisent au lieu
	 * d'échouer sur une version périmée, et ne peuvent pas s'interbloquer.
	 *
	 * @param unitCosts coût unitaire reçu par produit
	 */
	@Transactional
	public void recordPurchasePrices(Map<UUID, BigDecimal> unitCosts) {
		new TreeMap<>(unitCosts).forEach((productId, unitCost) -> {
			Product product = load(productId);
			this.entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
			product.recordPurchasePrice(unitCost);
		});
	}

	/**
	 * Produits actifs, d'une catégorie ou de tout le catalogue, triés par référence : périmètre
	 * d'un inventaire (module {@code inventory}).
	 */
	public List<ProductSnapshot> activeProducts(UUID categoryId) {
		return this.repository
			.findAll(ProductSpecifications.matching(new ProductFilter(null, categoryId, null, null, ActiveStatus.ACTIVE)),
					Sort.by("sku"))
			.stream()
			.map(ProductSnapshot::of)
			.toList();
	}

	/**
	 * Identifiants des produits (actifs ou non) dont la référence, le nom ou le code-barres contient
	 * le texte : recherches des autres modules (historiques, rapports). 500 au plus.
	 */
	public java.util.Set<UUID> idsMatching(String text) {
		return this.repository
			.findAll(ProductSpecifications.matching(new ProductFilter(text, null, null, null, ActiveStatus.ALL)),
					org.springframework.data.domain.PageRequest.of(0, 500))
			.getContent()
			.stream()
			.map(Product::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Produits existants (actifs ou non) parmi les identifiants demandés, pour les autres
	 * modules. Les identifiants inconnus sont absents du résultat.
	 */
	public Map<UUID, ProductSnapshot> snapshots(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return this.repository.findAllById(ids).stream()
			.collect(Collectors.toUnmodifiableMap(Product::getId, ProductSnapshot::of));
	}

	// --- Outils -------------------------------------------------------------------------

	/**
	 * Applique la requête. Catégorie, marque et fournisseur doivent être actifs s'ils changent ;
	 * un produit peut conserver une catégorie, une marque ou un fournisseur désactivé depuis.
	 */
	private void apply(Product product, ProductRequest request, String sku, String barcode, boolean creation) {
		boolean categoryChanged = creation || !product.getCategory().getId().equals(request.categoryId());
		Category category = categoryChanged ? this.categories.getForAssignment(request.categoryId(), true)
				: product.getCategory();

		Brand brand = null;
		if (request.brandId() != null) {
			boolean brandChanged = creation || product.getBrand() == null
					|| !product.getBrand().getId().equals(request.brandId());
			brand = brandChanged ? this.brands.getForAssignment(request.brandId(), true) : product.getBrand();
		}

		UUID supplierId = request.supplierId();
		if (supplierId != null && (creation || !supplierId.equals(product.getDefaultSupplierId()))) {
			this.suppliers.getForAssignment(supplierId, true);
		}

		product.identify(sku, barcode);
		product.describe(request.name().trim(), blankToNull(request.description()),
				request.unit() != null ? request.unit() : ProductUnit.UNIT);
		product.classify(category, brand, supplierId);
		product.reprice(request.purchasePrice(), request.retailPrice(), request.wholesalePrice());
		product.setStockThresholds(request.alertThreshold(), request.maxStockLevel());
	}

	private void requireUniqueIdentifiers(String sku, String barcode, UUID excludedId) {
		if (this.repository.existsBySkuIgnoringCase(sku, excludedId)) {
			throw new ConflictException(ErrorCode.SKU_ALREADY_USED, ErrorCode.SKU_ALREADY_USED.defaultMessage());
		}
		if (barcode != null && this.repository.existsByBarcode(barcode, excludedId)) {
			throw new ConflictException(ErrorCode.BARCODE_ALREADY_USED, ErrorCode.BARCODE_ALREADY_USED.defaultMessage());
		}
	}

	private Product load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage()));
	}

	/** Quantités lues auprès du module stock ; 0 tant qu'il n'est pas disponible. */
	private Map<UUID, Integer> stockOf(List<Product> products, UUID warehouseId) {
		ProductStockReader reader = this.stockReader.getIfAvailable();
		if (reader == null || products.isEmpty()) {
			return Map.of();
		}
		return reader.quantities(products.stream().map(Product::getId).toList(), warehouseId);
	}

	private static ProductResponse toResponse(Product product, Map<UUID, Integer> stock) {
		Category category = product.getCategory();
		Brand brand = product.getBrand();
		return new ProductResponse(product.getId(), product.getSku(), product.getBarcode(), product.getName(),
				product.getDescription(), category.getId(), category.getName(), brand != null ? brand.getId() : null,
				brand != null ? brand.getName() : null, product.getDefaultSupplierId(), product.getUnit(),
				product.getPurchasePrice(), product.getRetailPrice(), product.getWholesalePrice(),
				product.getAlertThreshold(), product.getMaxStockLevel(), stock.getOrDefault(product.getId(), 0),
				product.isActive(), product.getCreatedAt(), product.getUpdatedAt());
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
