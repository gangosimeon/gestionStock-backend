package com.geststock.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.settings.dto.InvoiceSettings;

/** Configuration des factures (ligne unique de {@code invoice_setting}, créée par V035). */
@Entity
@Table(name = "invoice_setting")
class InvoiceSetting extends AuditableEntity {

	@Column(name = "singleton", nullable = false, updatable = false)
	private boolean singleton = true;

	// --- Entreprise ---------------------------------------------------------------------------------

	@Column(name = "company_name", nullable = false, length = 150)
	private String companyName;

	@Column(name = "legal_name", nullable = false, length = 150)
	private String legalName;

	@Column(name = "slogan", nullable = false, length = 200)
	private String slogan;

	@Column(name = "address", nullable = false, length = 255)
	private String address;

	@Column(name = "city", nullable = false, length = 100)
	private String city;

	@Column(name = "country", nullable = false, length = 100)
	private String country;

	@Column(name = "phone", nullable = false, length = 50)
	private String phone;

	@Column(name = "email", nullable = false, length = 254)
	private String email;

	@Column(name = "website", nullable = false, length = 200)
	private String website;

	@Column(name = "registration_number", nullable = false, length = 100)
	private String registrationNumber;

	@Column(name = "tax_id", nullable = false, length = 100)
	private String taxId;

	// --- En-tête ------------------------------------------------------------------------------------

	@Column(name = "show_logo", nullable = false)
	private boolean showLogo;

	@Column(name = "show_company_name", nullable = false)
	private boolean showCompanyName;

	@Column(name = "show_slogan", nullable = false)
	private boolean showSlogan;

	@Column(name = "show_address", nullable = false)
	private boolean showAddress;

	@Column(name = "show_city_country", nullable = false)
	private boolean showCityCountry;

	@Column(name = "show_phone", nullable = false)
	private boolean showPhone;

	@Column(name = "show_email", nullable = false)
	private boolean showEmail;

	@Column(name = "show_website", nullable = false)
	private boolean showWebsite;

	@Column(name = "show_registration_number", nullable = false)
	private boolean showRegistrationNumber;

	@Column(name = "show_tax_id", nullable = false)
	private boolean showTaxId;

	@Column(name = "show_warehouse", nullable = false)
	private boolean showWarehouse;

	@Enumerated(EnumType.STRING)
	@Column(name = "logo_position", nullable = false, length = 10)
	private LogoPosition logoPosition;

	@Enumerated(EnumType.STRING)
	@Column(name = "header_alignment", nullable = false, length = 10)
	private TextAlignment headerAlignment;

	@Column(name = "header_separator", nullable = false)
	private boolean headerSeparator;

	@Column(name = "header_note", nullable = false, length = 300)
	private String headerNote;

	// --- Pied de page -------------------------------------------------------------------------------

	@Column(name = "thank_you_message", nullable = false, length = 300)
	private String thankYouMessage;

	@Column(name = "show_thank_you", nullable = false)
	private boolean showThankYou;

	@Column(name = "sales_terms", nullable = false, length = 1000)
	private String salesTerms;

	@Column(name = "show_sales_terms", nullable = false)
	private boolean showSalesTerms;

	@Column(name = "return_policy", nullable = false, length = 1000)
	private String returnPolicy;

	@Column(name = "show_return_policy", nullable = false)
	private boolean showReturnPolicy;

	@Column(name = "payment_terms", nullable = false, length = 500)
	private String paymentTerms;

	@Column(name = "show_payment_terms", nullable = false)
	private boolean showPaymentTerms;

	@Column(name = "payment_details", nullable = false, length = 500)
	private String paymentDetails;

	@Column(name = "show_payment_details", nullable = false)
	private boolean showPaymentDetails;

	@Column(name = "promo_message", nullable = false, length = 300)
	private String promoMessage;

	@Column(name = "show_promo_message", nullable = false)
	private boolean showPromoMessage;

	@Column(name = "legal_text", nullable = false, length = 1000)
	private String legalText;

	@Column(name = "show_legal_text", nullable = false)
	private boolean showLegalText;

	@Column(name = "signature_text", nullable = false, length = 200)
	private String signatureText;

	@Column(name = "show_signature", nullable = false)
	private boolean showSignature;

	@Column(name = "closing_text", nullable = false, length = 300)
	private String closingText;

	@Column(name = "show_closing_text", nullable = false)
	private boolean showClosingText;

	@Enumerated(EnumType.STRING)
	@Column(name = "footer_alignment", nullable = false, length = 10)
	private TextAlignment footerAlignment;

	@Column(name = "footer_separator", nullable = false)
	private boolean footerSeparator;

	@Column(name = "signature_name", nullable = false, length = 150)
	private String signatureName;

	// --- Apparence ----------------------------------------------------------------------------------

	@Enumerated(EnumType.STRING)
	@Column(name = "template", nullable = false, length = 10)
	private InvoiceTemplate template;

	@Column(name = "primary_color", nullable = false, length = 7)
	private String primaryColor;

	@Column(name = "secondary_color", nullable = false, length = 7)
	private String secondaryColor;

	@Column(name = "text_color", nullable = false, length = 7)
	private String textColor;

	@Column(name = "date_format", nullable = false, length = 20)
	private String dateFormat;

	@Column(name = "currency_label", nullable = false, length = 10)
	private String currencyLabel;

	@Enumerated(EnumType.STRING)
	@Column(name = "thousands_separator", nullable = false, length = 10)
	private ThousandsSeparator thousandsSeparator;

	@Column(name = "decimals", nullable = false)
	private int decimals;

	@Column(name = "show_line_number", nullable = false)
	private boolean showLineNumber;

	@Column(name = "show_sku", nullable = false)
	private boolean showSku;

	@Column(name = "show_discount_column", nullable = false)
	private boolean showDiscountColumn;

	@Column(name = "show_amount_in_words", nullable = false)
	private boolean showAmountInWords;

	@Column(name = "show_page_numbers", nullable = false)
	private boolean showPageNumbers;

	@Column(name = "page_footer_text", nullable = false, length = 200)
	private String pageFooterText;

	@Column(name = "show_page_footer_text", nullable = false)
	private boolean showPageFooterText;

	@Enumerated(EnumType.STRING)
	@Column(name = "table_header_alignment", nullable = false, length = 10)
	private TableHeaderAlignment tableHeaderAlignment;

	@Enumerated(EnumType.STRING)
	@Column(name = "font_family", nullable = false, length = 20)
	private InvoiceFont fontFamily;

	protected InvoiceSetting() {
	}

	InvoiceSettings values() {
		return new InvoiceSettings(
				new InvoiceSettings.Company(this.companyName, this.legalName, this.slogan, this.address, this.city,
						this.country, this.phone, this.email, this.website, this.registrationNumber, this.taxId),
				new InvoiceSettings.Header(this.showLogo, this.showCompanyName, this.showSlogan, this.showAddress,
						this.showCityCountry, this.showPhone, this.showEmail, this.showWebsite,
						this.showRegistrationNumber, this.showTaxId, this.showWarehouse, this.logoPosition,
						this.headerAlignment, this.headerSeparator, this.headerNote),
				new InvoiceSettings.Footer(this.thankYouMessage, this.showThankYou, this.salesTerms, this.showSalesTerms,
						this.returnPolicy, this.showReturnPolicy, this.paymentTerms, this.showPaymentTerms,
						this.paymentDetails, this.showPaymentDetails, this.promoMessage, this.showPromoMessage,
						this.legalText, this.showLegalText, this.signatureText, this.showSignature, this.closingText,
						this.showClosingText, this.footerAlignment, this.footerSeparator, this.signatureName),
				new InvoiceSettings.Appearance(this.template, this.primaryColor, this.secondaryColor, this.textColor,
						this.dateFormat, this.currencyLabel, this.thousandsSeparator, this.decimals,
						this.showLineNumber, this.showSku, this.showDiscountColumn, this.showAmountInWords,
						this.showPageNumbers, this.pageFooterText, this.showPageFooterText, this.tableHeaderAlignment,
						this.fontFamily));
	}

	void apply(InvoiceSettings values) {
		InvoiceSettings.Company c = values.company();
		this.companyName = c.companyName();
		this.legalName = c.legalName();
		this.slogan = c.slogan();
		this.address = c.address();
		this.city = c.city();
		this.country = c.country();
		this.phone = c.phone();
		this.email = c.email();
		this.website = c.website();
		this.registrationNumber = c.registrationNumber();
		this.taxId = c.taxId();

		InvoiceSettings.Header h = values.header();
		this.showLogo = h.showLogo();
		this.showCompanyName = h.showCompanyName();
		this.showSlogan = h.showSlogan();
		this.showAddress = h.showAddress();
		this.showCityCountry = h.showCityCountry();
		this.showPhone = h.showPhone();
		this.showEmail = h.showEmail();
		this.showWebsite = h.showWebsite();
		this.showRegistrationNumber = h.showRegistrationNumber();
		this.showTaxId = h.showTaxId();
		this.showWarehouse = h.showWarehouse();
		this.logoPosition = h.logoPosition();
		this.headerAlignment = h.headerAlignment();
		this.headerSeparator = h.headerSeparator();
		this.headerNote = h.headerNote();

		InvoiceSettings.Footer f = values.footer();
		this.thankYouMessage = f.thankYouMessage();
		this.showThankYou = f.showThankYou();
		this.salesTerms = f.salesTerms();
		this.showSalesTerms = f.showSalesTerms();
		this.returnPolicy = f.returnPolicy();
		this.showReturnPolicy = f.showReturnPolicy();
		this.paymentTerms = f.paymentTerms();
		this.showPaymentTerms = f.showPaymentTerms();
		this.paymentDetails = f.paymentDetails();
		this.showPaymentDetails = f.showPaymentDetails();
		this.promoMessage = f.promoMessage();
		this.showPromoMessage = f.showPromoMessage();
		this.legalText = f.legalText();
		this.showLegalText = f.showLegalText();
		this.signatureText = f.signatureText();
		this.showSignature = f.showSignature();
		this.closingText = f.closingText();
		this.showClosingText = f.showClosingText();
		this.footerAlignment = f.footerAlignment();
		this.footerSeparator = f.footerSeparator();
		this.signatureName = f.signatureName();

		InvoiceSettings.Appearance a = values.appearance();
		this.template = a.template();
		this.primaryColor = a.primaryColor();
		this.secondaryColor = a.secondaryColor();
		this.textColor = a.textColor();
		this.dateFormat = a.dateFormat();
		this.currencyLabel = a.currencyLabel();
		this.thousandsSeparator = a.thousandsSeparator();
		this.decimals = a.decimals();
		this.showLineNumber = a.showLineNumber();
		this.showSku = a.showSku();
		this.showDiscountColumn = a.showDiscountColumn();
		this.showAmountInWords = a.showAmountInWords();
		this.showPageNumbers = a.showPageNumbers();
		this.pageFooterText = a.pageFooterText();
		this.showPageFooterText = a.showPageFooterText();
		this.tableHeaderAlignment = a.tableHeaderAlignment();
		this.fontFamily = a.fontFamily();
	}

}
