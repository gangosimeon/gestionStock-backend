package com.geststock.purchase;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Facture fournisseur (V026) de bout en bout : commande reçue ou soldée seulement, montant = valeur
 * reçue, numéro unique par fournisseur, date contrôlée, facture figée, droits et audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class SupplierInvoiceApiIT {

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID supplier;

	private UUID otherSupplier;

	private UUID frame;

	@BeforeEach
	void setUp() {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('supplier_invoice_mgr', 'Comptable') returning id",
				UUID.class);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name) values ('FOU-INV', 'Essilor Factures') returning id""", UUID.class);
		this.otherSupplier = this.jdbc.queryForObject("""
				insert into supplier (code, name) values ('FOU-INV2', 'Autre fournisseur factures') returning id""",
				UUID.class);
		this.frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('INV-MON-1', 'Monture facture', (select id from category where name = 'Montures'), 10000, 20000, 18000)
				returning id""", UUID.class);
	}

	@Test
	void aReceivedOrderIsInvoicedOnceForItsReceivedValue() throws Exception {
		String order = createOrder(this.supplier, 3); // 30 000
		invoice(order, "FA-1", TODAY).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_INVOICEABLE"));

		receive(order, "{}");
		invoice(order, "FA-ESS-0412", TODAY)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.purchaseOrderId").value(order))
			.andExpect(jsonPath("$.supplierId").value(this.supplier.toString()))
			.andExpect(jsonPath("$.invoiceNumber").value("FA-ESS-0412"))
			.andExpect(jsonPath("$.invoiceDateIso").value(TODAY.toString()))
			.andExpect(jsonPath("$.totalAmount").value(30000))
			.andExpect(jsonPath("$.invoicedByUserId").value(this.manager.toString()));

		this.mvc.perform(get("/api/purchases/orders/{id}", order).with(manager()))
			.andExpect(jsonPath("$.invoice.invoiceNumber").value("FA-ESS-0412"))
			.andExpect(jsonPath("$.invoice.totalAmount").value(30000));
		invoice(order, "FA-AUTRE", TODAY).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_ALREADY_INVOICED"));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'INVOICE' and entity_type = 'PURCHASE_ORDER'
				and entity_id = ? and status = 'SUCCESS'""", Integer.class, order)).isOne();
	}

	@Test
	void aClosedOrderIsInvoicedForWhatWasActuallyDelivered() throws Exception {
		String order = createOrder(this.supplier, 10); // 100 000
		receive(order, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 4}]}".formatted(this.frame));
		invoice(order, "FA-PART", TODAY).andExpect(status().isConflict());
		this.mvc.perform(post("/api/purchases/orders/{id}/close", order).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Fin\"}")).andExpect(status().isOk());
		invoice(order, "FA-PART", TODAY).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(40000));
	}

	@Test
	void theNumberIsUniquePerSupplierAndTheDateIsChecked() throws Exception {
		String first = receivedOrder(this.supplier);
		String second = receivedOrder(this.supplier);
		String foreign = receivedOrder(this.otherSupplier);
		invoice(first, "FA-100", TODAY).andExpect(status().isCreated());

		invoice(second, "fa-100", TODAY).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUPPLIER_INVOICE_NUMBER_ALREADY_USED"));
		// Même numéro chez un autre fournisseur : accepté.
		invoice(foreign, "FA-100", TODAY).andExpect(status().isCreated());

		invoice(second, "FA-101", TODAY.plusDays(1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SUPPLIER_INVOICE_DATE_INVALID"));
		invoice(second, "FA-101", TODAY.minusYears(1)).andExpect(status().isBadRequest());
		this.mvc.perform(post("/api/purchases/orders/{id}/invoice", second).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"invoiceNumber\": \"\", \"invoiceDateIso\": \"%s\"}"
				.formatted(TODAY)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		// Contrat du front : invoiceDateIso.
		this.mvc.perform(post("/api/purchases/orders/{id}/invoice", second).with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"invoiceNumber\": \"FA-101\", \"invoiceDateIso\": \"%s\"}".formatted(TODAY)))
			.andExpect(status().isCreated());
	}

	@Test
	void theDatabaseFreezesARecordedInvoice() throws Exception {
		String order = receivedOrder(this.supplier);
		invoice(order, "FA-DB", TODAY).andExpect(status().isCreated());

		assertThatThrownBy(() -> this.jdbc.update(
				"update purchase_order set invoice_number = 'FA-DB-2' where id = ?::uuid", order))
			.hasMessageContaining("déjà enregistrée");
	}

	@Test
	void theDatabaseRejectsADuplicateNumberForTheSameSupplier() throws Exception {
		String first = receivedOrder(this.supplier);
		String second = receivedOrder(this.supplier);
		invoice(first, "FA-DB", TODAY).andExpect(status().isCreated());

		assertThatThrownBy(() -> this.jdbc.update("""
				update purchase_order set invoice_number = 'FA-DB', invoice_date = current_date,
				    invoiced_amount = received_amount, invoiced_at = now() where id = ?::uuid""", second))
			.hasMessageContaining("uk_purchase_order_supplier_invoice");
	}

	@Test
	void onlyBuyersAllowedToPayRecordInvoices() throws Exception {
		String order = receivedOrder(this.supplier);
		RequestPostProcessor receiver = actor(Permissions.PURCHASE_READ, Permissions.PURCHASE_RECEIVE);
		this.mvc.perform(post("/api/purchases/orders/{id}/invoice", order).with(receiver)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"invoiceNumber\": \"FA-X\", \"invoiceDate\": \"%s\"}".formatted(TODAY)))
			.andExpect(status().isForbidden());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private String receivedOrder(UUID supplierId) throws Exception {
		String order = createOrder(supplierId, 1);
		receive(order, "{}");
		return order;
	}

	private String createOrder(UUID supplierId, int quantity) throws Exception {
		return idOf(this.mvc.perform(post("/api/purchases/orders").with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": %d, "unitPurchasePrice": 10000}]}"""
				.formatted(supplierId, this.frame, quantity))));
	}

	private void receive(String orderId, String body) throws Exception {
		this.mvc.perform(post("/api/purchases/orders/{id}/receive", orderId).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
	}

	private ResultActions invoice(String orderId, String number, LocalDate date) throws Exception {
		return this.mvc.perform(post("/api/purchases/orders/{id}/invoice", orderId).with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"invoiceNumber\": \"%s\", \"invoiceDate\": \"%s\"}".formatted(number, date)));
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.manager, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
