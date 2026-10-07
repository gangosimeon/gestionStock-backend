package com.geststock.stock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.data.repository.CrudRepository;

import com.geststock.product.Product;
import com.geststock.product.dto.ProductRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une seule source de vérité, un seul point d'écriture : vérifications structurelles qui
 * cassent le build si quelqu'un ouvre une autre voie d'écriture du stock.
 */
class StockArchitectureTest {

	@Test
	void stockLevelExposesNoPublicMutator() {
		List<String> publicMutators = Arrays.stream(StockLevel.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()))
			.map(Method::getName)
			.filter(name -> !name.startsWith("get") && !name.startsWith("is"))
			.toList();
		assertThat(publicMutators).isEmpty();
	}

	@Test
	void stockMovementIsImmutableOncePublic() {
		List<String> publicMutators = Arrays.stream(StockMovement.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()))
			.map(Method::getName)
			.filter(name -> !name.startsWith("get") && !List.of("equals", "hashCode", "toString").contains(name))
			.toList();
		assertThat(publicMutators).isEmpty();
		assertThat(StockMovement.class.getAnnotation(org.hibernate.annotations.Immutable.class)).isNotNull();
	}

	@Test
	void repositoriesOfferNoGenericWriteMethods() {
		assertThat(CrudRepository.class.isAssignableFrom(StockLevelRepository.class)).isFalse();
		assertThat(CrudRepository.class.isAssignableFrom(StockMovementRepository.class)).isFalse();
		assertThat(Arrays.stream(StockLevelRepository.class.getMethods()).map(Method::getName))
			.noneMatch(name -> name.startsWith("save") || name.startsWith("delete") || name.startsWith("update"));
		assertThat(Arrays.stream(StockMovementRepository.class.getMethods()).map(Method::getName))
			.as("JpaSpecificationExecutor expose update/delete en masse : interdit ici")
			.noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
	}

	@Test
	void productIsNotASecondSourceOfTruth() {
		assertThat(Arrays.stream(Product.class.getDeclaredFields()).map(Field::getName))
			.noneMatch(name -> name.toLowerCase().contains("stockquantity") || name.equalsIgnoreCase("quantity"));
		assertThat(Arrays.stream(ProductRequest.class.getRecordComponents()).map(c -> c.getName()))
			.as("stockQuantity n'est pas lu à l'écriture d'un produit")
			.doesNotContain("stockQuantity");
	}

}
