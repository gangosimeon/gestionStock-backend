package com.geststock.platform.company.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contrats de l'API Super Admin des entreprises ({@code /api/platform/companies}). */
public final class CompanyDtos {

	/** Code public : minuscules, chiffres et tirets, 2 à 40 caractères. */
	public static final String CODE_PATTERN = "^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$";

	public static final String PLAN_PATTERN = "^(ESSENTIEL|STANDARD|PREMIUM|ENTREPRISE)$";

	private CompanyDtos() {
	}

	@Schema(name = "CreateCompanyRequest")
	public record CreateCompanyRequest(
			@NotBlank @Pattern(regexp = CODE_PATTERN, message = "minuscules, chiffres et tirets (2 à 40 caractères)")
			@Schema(description = "Code public (connexion, marque). Jamais utilisé comme nom de base.", example = "quincaillerie-akwaba")
			String code,
			@NotBlank @Size(max = 150) @Schema(example = "Quincaillerie Akwaba SARL") String legalName,
			@NotBlank @Size(max = 150) @Schema(example = "Quincaillerie Akwaba") String tradeName,
			@Size(max = 100) @Schema(example = "Quincaillerie") String sector,
			@Size(max = 255) String address,
			@Size(max = 100) String city,
			@Size(max = 100) String country,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$") String phone,
			@Email @Size(max = 254) String email,
			@Size(max = 200) String website,
			@Size(max = 100) String taxId,
			@Size(max = 10) String currency,
			@Size(max = 100) String ownerFirstName,
			@NotBlank @Size(max = 100) String ownerLastName,
			@Email @Size(max = 254) String ownerEmail,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$") String ownerPhone,
			@NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$", message = "3 à 50 caractères : lettres, chiffres, . _ -")
			@Schema(description = "Identifiant de connexion de l'administrateur de l'entreprise", example = "admin")
			String adminUsername,
			@Pattern(regexp = PLAN_PATTERN) @Schema(example = "STANDARD") String plan,
			LocalDate subscriptionStartsOn,
			LocalDate subscriptionEndsOn,
			@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
			@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String secondaryColor) {
	}

	@Schema(name = "UpdateCompanyRequest")
	public record UpdateCompanyRequest(
			@NotBlank @Size(max = 150) String legalName,
			@NotBlank @Size(max = 150) String tradeName,
			@Size(max = 100) String sector,
			@Size(max = 255) String address,
			@Size(max = 100) String city,
			@Size(max = 100) String country,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$") String phone,
			@Email @Size(max = 254) String email,
			@Size(max = 200) String website,
			@Size(max = 100) String taxId,
			@Size(max = 10) String currency,
			@Size(max = 100) String ownerFirstName,
			@NotBlank @Size(max = 100) String ownerLastName,
			@Email @Size(max = 254) String ownerEmail,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$") String ownerPhone,
			@Pattern(regexp = PLAN_PATTERN) String plan,
			LocalDate subscriptionStartsOn,
			LocalDate subscriptionEndsOn) {
	}

	@Schema(name = "SuspendCompanyRequest")
	public record SuspendCompanyRequest(@NotBlank @Size(max = 300) @Schema(example = "Abonnement impayé") String reason) {
	}

	@Schema(name = "CompanyListItem")
	public record CompanyListItem(UUID id, int number, String code, String legalName, String tradeName, String city,
			String plan, String status, LocalDate subscriptionEndsOn, Instant createdAt) {
	}

	@Schema(name = "CompanyDatabase", description = "Base de l'entreprise (aucun identifiant de connexion n'est exposé)")
	public record DatabaseInfo(String name, String mode) {
	}

	@Schema(name = "ProvisioningStep")
	public record ProvisioningStepInfo(String step, String status, String message, int attempt, Instant occurredAt) {
	}

	@Schema(name = "Provisioning")
	public record ProvisioningInfo(String status, String currentStep, int attempts, String lastError, Instant startedAt,
			Instant finishedAt, List<ProvisioningStepInfo> steps) {
	}

	@Schema(name = "Company")
	public record CompanyResponse(UUID id, int number, String code, String legalName, String tradeName, String sector,
			String address, String city, String country, String phone, String email, String website, String taxId,
			String currency, String ownerFirstName, String ownerLastName, String ownerEmail, String ownerPhone,
			String adminUsername, String plan, LocalDate subscriptionStartsOn, LocalDate subscriptionEndsOn,
			String status, String statusReason, Instant createdAt, Instant updatedAt, DatabaseInfo database,
			ProvisioningInfo provisioning) {
	}

	@Schema(name = "ActivationLink", description = "Lien d'activation de l'administrateur : montré une seule fois")
	public record ActivationLinkResponse(String companyCode, String username, String token, Instant expiresAt) {

		@Override
		public String toString() {
			return "ActivationLinkResponse[companyCode=" + this.companyCode + ", username=" + this.username
					+ ", token=***]";
		}

	}

	@Schema(name = "PlatformStats")
	public record PlatformStats(long total, long active, long suspended, long provisioning, long failed,
			long expiringWithin30Days, List<CompanyListItem> recent) {
	}

	@Schema(name = "PlatformAuditEntry")
	public record AuditEntry(UUID id, Instant occurredAt, String actorName, String action, UUID companyId,
			String companyCode, String details) {
	}

}
