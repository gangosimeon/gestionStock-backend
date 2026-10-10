package com.geststock.security;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.permission.PermissionService;
import com.geststock.role.RoleService;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cohérence du RBAC entre le code et la base : une permission vérifiée par un endpoint doit
 * exister dans le catalogue (sinon personne ne peut y accéder), et la matrice livrée doit
 * correspondre à l'architecture validée.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PermissionCatalogIT {

	private static final Pattern QUOTED_PERMISSION = Pattern.compile("'([a-z][a-z-]*:[a-z][a-z-]*)'");

	@Autowired
	private PermissionService permissions;

	@Autowired
	private RoleService roles;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void javaConstantsMirrorTheDatabaseCatalog() {
		assertThat(new TreeSet<>(this.permissions.allCodes())).isEqualTo(new TreeSet<>(Permissions.ALL));
	}

	@Test
	void everyPermissionCheckedByAnEndpointExistsInTheCatalog() {
		Set<String> referenced = new TreeSet<>();
		this.handlerMapping.getHandlerMethods().values().forEach(handler -> {
			collect(handler.getMethod(), referenced);
			collect(handler.getBeanType(), referenced);
		});
		assertThat(referenced).as("au moins user:manage et role:manage").contains("user:manage", "role:manage");
		assertThat(Permissions.ALL).containsAll(referenced);
	}

	@Test
	void adminAlwaysHoldsEveryPermission() {
		assertThat(this.roles.permissionsOf(Set.of("ADMIN"))).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
	}

	@Test
	void builtInMatrixMatchesTheArchitecture() {
		Set<String> manager = this.roles.permissionsOf(Set.of("GESTIONNAIRE"));
		assertThat(manager).hasSize(39)
			.contains(Permissions.PRODUCT_CREATE, Permissions.PRODUCT_UPDATE, Permissions.STOCK_ADJUST,
					Permissions.PURCHASE_RECEIVE, Permissions.INVENTORY_VALIDATE, Permissions.SALE_CANCEL,
					Permissions.CUSTOMER_CREDIT)
			.doesNotContain(Permissions.PRODUCT_DELETE, Permissions.USER_MANAGE, Permissions.ROLE_MANAGE,
					Permissions.AUDIT_READ);

		Set<String> cashier = this.roles.permissionsOf(Set.of("CAISSIER"));
		// supplier:read depuis V014 : l'écran Produits affiche le fournisseur habituel.
		assertThat(cashier).hasSize(15)
			.contains(Permissions.SALE_CREATE, Permissions.CASH_OPERATE, Permissions.CUSTOMER_PAY,
					Permissions.SUPPLIER_READ)
			.doesNotContain(Permissions.STOCK_ADJUST, Permissions.SALE_CANCEL, Permissions.CASH_READ,
					Permissions.REPORT_READ, Permissions.SUPPLIER_CREATE, Permissions.SUPPLIER_UPDATE,
					Permissions.SUPPLIER_DELETE, Permissions.CUSTOMER_CREDIT);
	}

	@Test
	void noRoleMeansNoPermission() {
		assertThat(this.roles.permissionsOf(Set.of())).isEmpty();
		assertThat(this.roles.permissionsOf(Set.of("ROLE_INEXISTANT"))).isEmpty();
	}

	private static void collect(java.lang.reflect.AnnotatedElement element, Set<String> into) {
		RequiresPermission requires = AnnotatedElementUtils.findMergedAnnotation(element, RequiresPermission.class);
		if (requires != null) {
			into.add(requires.value());
			return;
		}
		PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
		if (preAuthorize != null) {
			Matcher matcher = QUOTED_PERMISSION.matcher(preAuthorize.value());
			while (matcher.find()) {
				into.add(matcher.group(1));
			}
		}
	}

}
