package com.geststock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.geststock.customer.PriceCategory;
import com.geststock.product.ProductSnapshot;

/**
 * Calcul serveur des montants d'une vente (RG-SAL-5, RG-SAL-6). Aucun prix ni coût ne vient
 * du client : seuls le produit, la quantité et la remise demandée sont pris en compte.
 */
final class SalePricing {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private SalePricing() {
	}

	/** Prix unitaire du tarif appliqué : prix de détail ou prix de gros du produit. */
	static BigDecimal unitPrice(ProductSnapshot product, PriceCategory priceType) {
		BigDecimal price = priceType == PriceCategory.WHOLESALE ? product.wholesalePrice() : product.retailPrice();
		return price.setScale(2, RoundingMode.UNNECESSARY);
	}

	/**
	 * Montants d'une ligne.
	 *
	 * @param discountPercent remise en %, déjà contrôlée (0 à 100)
	 */
	static LineAmounts line(BigDecimal unitPrice, int quantity, BigDecimal discountPercent, BigDecimal unitCost) {
		BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
		BigDecimal discount = gross.multiply(discountPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
		BigDecimal cost = unitCost.setScale(2, RoundingMode.UNNECESSARY);
		return new LineAmounts(gross, discount, gross.subtract(discount),
				cost.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY), cost);
	}

	/**
	 * @param gross     prix unitaire × quantité
	 * @param discount  remise de la ligne (arrondie au centime, au plus près)
	 * @param net       montant de la ligne après remise
	 * @param costTotal coût unitaire × quantité
	 * @param unitCost  coût unitaire
	 */
	record LineAmounts(BigDecimal gross, BigDecimal discount, BigDecimal net, BigDecimal costTotal, BigDecimal unitCost) {
	}

}
