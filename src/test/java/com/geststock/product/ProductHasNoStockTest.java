package com.geststock.product;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.geststock.product.dto.ProductRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : le produit ne doit jamais redevenir une seconde source de vérité du stock
 * (défaut constaté dans l'ancien mock : {@code Product.stockQuantity} divergeait du stock réel).
 */
class ProductHasNoStockTest {

	@Test
	void productEntityHoldsNoQuantity() {
		assertThat(Arrays.stream(Product.class.getDeclaredFields()).map(Field::getName))
			.noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("stockquantity")
					|| name.toLowerCase(Locale.ROOT).equals("quantity"));
	}

	@Test
	void productRequestCannotCarryAQuantity() {
		assertThat(Arrays.stream(ProductRequest.class.getRecordComponents()).map(RecordComponent::getName))
			.noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("quantity"));
	}

}
