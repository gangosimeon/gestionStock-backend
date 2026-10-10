package com.geststock.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lignes et totaux des rapports (règles de calcul : {@code docs/reports.md}). */
public final class ReportRows {

	private ReportRows() {
	}

	// --- Ventes -------------------------------------------------------------------------------------

	/** Vente de la période ; {@code netAmount} = total − montants retournés. */
	@Schema(name = "SaleReportRow")
	public record SaleRow(UUID id, String number, Instant createdAt, UUID warehouseId, String warehouseName,
			UUID customerId, String customerName, UUID sellerId, String sellerName, String status, String paymentMethod,
			BigDecimal total, BigDecimal cost, BigDecimal profit, BigDecimal paidAmount, BigDecimal creditAmount,
			BigDecimal returnedAmount, BigDecimal netAmount) {
	}

	/** Totaux des ventes validées du filtre ({@code netSales} = total − retours sur ces ventes). */
	@Schema(name = "SaleReportSummary")
	public record SaleSummary(long completedCount, long cancelledCount, BigDecimal total, BigDecimal cost,
			BigDecimal profit, BigDecimal returnedAmount, BigDecimal netSales, BigDecimal creditAmount) {
	}

	/** Ventes d'un produit (ventes de la période, retours de la période). */
	@Schema(name = "ProductSalesReportRow")
	public record ProductSalesRow(UUID productId, String sku, String name, String categoryName, long soldQuantity,
			long returnedQuantity, long netQuantity, BigDecimal revenue, BigDecimal returns, BigDecimal netRevenue,
			BigDecimal cost, BigDecimal profit, BigDecimal marginRate) {
	}

	@Schema(name = "ProductSalesReportSummary")
	public record ProductSalesSummary(long products, long netQuantity, BigDecimal netRevenue, BigDecimal cost,
			BigDecimal profit) {
	}

	/** Point d'une série de ventes (jour, mois ou année). */
	@Schema(name = "SalesPeriodReportRow")
	public record SalesPeriodRow(LocalDate period, long count, BigDecimal grossSales, BigDecimal returns,
			BigDecimal netSales, BigDecimal cost, BigDecimal profit) {
	}

	/** Ventes par vendeur ou par dépôt. */
	@Schema(name = "SalesGroupReportRow")
	public record SalesGroupRow(UUID id, String label, long count, BigDecimal grossSales, BigDecimal returns,
			BigDecimal netSales, BigDecimal profit, BigDecimal averageBasket) {
	}

	// --- Achats -------------------------------------------------------------------------------------

	@Schema(name = "PurchaseOrderReportRow")
	public record PurchaseOrderRow(UUID id, String number, Instant createdAt, UUID supplierId, String supplierName,
			UUID warehouseId, String warehouseName, String status, LocalDate expectedDeliveryDate, Instant lastReceiptAt,
			BigDecimal totalAmount, BigDecimal receivedAmount, BigDecimal remainingAmount, boolean overdue,
			String createdByName) {
	}

	@Schema(name = "PurchaseReportSummary")
	public record PurchaseSummary(long count, BigDecimal totalAmount, BigDecimal receivedAmount,
			BigDecimal remainingAmount, long overdueCount) {
	}

	/** Fournisseur : commandes et réceptions de la période, reste à recevoir actuel. */
	@Schema(name = "SupplierReportRow")
	public record SupplierRow(UUID supplierId, String code, String name, boolean active, long ordersCount,
			BigDecimal orderedAmount, long receiptsCount, BigDecimal receivedAmount, BigDecimal openRemainingAmount,
			long overdueOrders, Instant lastReceiptAt, BigDecimal averageDeliveryDays, int deliveryLeadTimeDays) {
	}

	@Schema(name = "SupplierReportSummary")
	public record SupplierSummary(long suppliers, BigDecimal orderedAmount, BigDecimal receivedAmount,
			BigDecimal openRemainingAmount) {
	}

	// --- Stock --------------------------------------------------------------------------------------

	/**
	 * État et valorisation d'un produit.
	 *
	 * @param soldQuantity  sorties par vente sur la période (nettes des annulations de vente)
	 * @param coverageDays  jours de stock au rythme des ventes de la période ({@code null} sans vente)
	 * @param lastExitAt    dernière sortie (vente, transfert)
	 */
	@Schema(name = "StockReportRow")
	public record StockRow(UUID productId, String sku, String name, String categoryName, boolean active,
			long quantity, BigDecimal unitCost, BigDecimal value, int alertThreshold, Integer maxStockLevel,
			long soldQuantity, BigDecimal coverageDays, Instant lastExitAt) {
	}

	@Schema(name = "StockReportSummary")
	public record StockSummary(long products, long units, BigDecimal value, long outOfStock) {
	}

	@Schema(name = "StockMovementReportRow")
	public record MovementRow(UUID id, Instant createdAt, UUID warehouseId, String warehouseName, UUID productId,
			String sku, String productName, String type, int quantity, int balanceAfter, BigDecimal unitCost,
			BigDecimal value, String reason, String sourceType, String sourceReference, UUID userId, String userName) {
	}

	@Schema(name = "StockMovementReportSummary")
	public record MovementSummary(long count, long quantityIn, long quantityOut, BigDecimal valueIn,
			BigDecimal valueOut) {
	}

	@Schema(name = "StockMovementTypeReportRow")
	public record MovementTypeRow(String type, long count, long quantity, BigDecimal value) {
	}

	// --- Inventaires --------------------------------------------------------------------------------

	@Schema(name = "InventoryReportRow")
	public record InventoryRow(UUID id, String number, UUID warehouseId, String warehouseName, String status,
			String scope, Instant createdAt, Instant validatedAt, String validatedByName, long lines, long countedLines,
			long gapLines, long quantityGain, long quantityLoss, BigDecimal valueDifference) {
	}

	@Schema(name = "InventoryReportSummary")
	public record InventorySummary(long count, long completed, long quantityGain, long quantityLoss,
			BigDecimal valueDifference) {
	}

	// --- Caisse -------------------------------------------------------------------------------------

	/** Session de caisse : espèces calculées depuis ses écritures, comptage et écart à la clôture. */
	@Schema(name = "CashSessionReportRow")
	public record CashSessionRow(UUID id, String number, UUID warehouseId, String warehouseName, UUID cashierId,
			String cashierName, String terminal, String status, Instant openedAt, Instant closedAt,
			BigDecimal openingBalance, BigDecimal cashSales, BigDecimal cashIn, BigDecimal cashOut,
			BigDecimal nonCashReceipts, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference) {
	}

	@Schema(name = "CashReportSummary")
	public record CashSummary(long sessions, long open, BigDecimal cashSales, BigDecimal nonCashReceipts,
			BigDecimal totalDifference, long sessionsWithDifference) {
	}

	// --- Clients ------------------------------------------------------------------------------------

	/** Client : créance actuelle (échue ou non) et activité de la période. */
	@Schema(name = "CustomerReportRow")
	public record CustomerRow(UUID customerId, String code, String name, boolean active, BigDecimal creditLimit,
			int paymentTermsDays, BigDecimal balance, BigDecimal overdueAmount, Instant oldestDueAt, long salesCount,
			BigDecimal salesAmount, BigDecimal paymentsAmount, Instant lastSaleAt) {
	}

	@Schema(name = "CustomerReportSummary")
	public record CustomerSummary(long customers, long debtors, BigDecimal balance, BigDecimal overdueAmount,
			BigDecimal salesAmount, BigDecimal paymentsAmount) {
	}

}
