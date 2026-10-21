package com.geststock.platform.company;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SearchText;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.audit.PlatformAudit;
import com.geststock.platform.company.dto.CompanyDtos.ActivationLinkResponse;
import com.geststock.platform.company.dto.CompanyDtos.AuditEntry;
import com.geststock.platform.company.dto.CompanyDtos.CompanyListItem;
import com.geststock.platform.company.dto.CompanyDtos.CompanyResponse;
import com.geststock.platform.company.dto.CompanyDtos.CreateCompanyRequest;
import com.geststock.platform.company.dto.CompanyDtos.DatabaseInfo;
import com.geststock.platform.company.dto.CompanyDtos.PlatformStats;
import com.geststock.platform.company.dto.CompanyDtos.ProvisioningInfo;
import com.geststock.platform.company.dto.CompanyDtos.ProvisioningStepInfo;
import com.geststock.platform.company.dto.CompanyDtos.UpdateCompanyRequest;
import com.geststock.platform.provisioning.ProvisioningService;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.AccountAccessService;
import com.geststock.user.UserAccountService;

/**
 * Entreprises vues par le Super Admin : création (idempotente), consultation, modification,
 * suspension, réactivation, relance du provisionnement, lien d'activation de l'administrateur.
 * Toute opération est tracée dans le journal de la plateforme.
 */
@Service
public class CompanyService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("tradeName", "lower(c.trade_name)", "code", "c.code",
			"number", "c.number", "createdAt", "c.created_at", "status", "c.status", "subscriptionEndsOn",
			"c.subscription_ends_on"), Sort.by(Sort.Direction.DESC, "c.created_at"), List.of("c.id"));

	private static final String SELECT = """
			select c.*, d.database_name, d.mode as database_mode, j.status as job_status, j.current_step, j.attempts,
			       j.last_error, j.started_at as job_started_at, j.finished_at as job_finished_at
			from platform.company c
			left join platform.company_database d on d.company_id = c.id
			left join platform.provisioning_job j on j.company_id = c.id
			""";

	private final PlatformDb db;

	private final PlatformAudit audit;

	private final ProvisioningService provisioning;

	private final CompanyDirectory directory;

	private final TenantDataSourceRegistry pools;

	private final UserAccountService accounts;

	private final AccountAccessService accessLinks;

	private final NamedParameterJdbcTemplate tenantJdbc;

	private final Clock clock;

	CompanyService(PlatformDb db, PlatformAudit audit, ProvisioningService provisioning, CompanyDirectory directory,
			TenantDataSourceRegistry pools, UserAccountService accounts, AccountAccessService accessLinks,
			NamedParameterJdbcTemplate tenantJdbc, Clock clock) {
		this.db = db;
		this.audit = audit;
		this.provisioning = provisioning;
		this.directory = directory;
		this.pools = pools;
		this.accounts = accounts;
		this.accessLinks = accessLinks;
		this.tenantJdbc = tenantJdbc;
		this.clock = clock;
	}

	/**
	 * Crée l'entreprise et lance son provisionnement. Même clé d'idempotence : l'entreprise déjà créée est
	 * renvoyée, rien n'est recréé ni relancé.
	 *
	 * @return entreprise et {@code true} si elle vient d'être créée
	 */
	public Created create(CreateCompanyRequest request, String idempotencyKey) {
		String key = normalizeKey(idempotencyKey);
		if (key != null) {
			Optional<UUID> existing = this.db.jdbc()
				.sql("select id from platform.company where idempotency_key = :key")
				.param("key", key)
				.query(UUID.class)
				.optional();
			if (existing.isPresent()) {
				return new Created(get(existing.get()), false);
			}
		}
		checkDates(request.subscriptionStartsOn(), request.subscriptionEndsOn());
		String code = request.code().trim().toLowerCase(Locale.ROOT);
		UUID id = UUID.randomUUID();
		try {
			this.db.inTransaction(() -> {
				this.db.jdbc()
					.sql("""
							insert into platform.company (id, code, legal_name, trade_name, sector, address, city, country, phone,
							    email, website, tax_id, currency, owner_first_name, owner_last_name, owner_email, owner_phone,
							    admin_username, plan, subscription_starts_on, subscription_ends_on, status, idempotency_key, created_by)
							values (:id, :code, :legalName, :tradeName, :sector, :address, :city, :country, :phone, :email,
							    :website, :taxId, :currency, :ownerFirstName, :ownerLastName, :ownerEmail, :ownerPhone,
							    :adminUsername, :plan, :startsOn, :endsOn, 'PENDING', :key, :createdBy)""")
					.param("id", id)
					.param("code", code)
					.param("legalName", request.legalName().trim())
					.param("tradeName", request.tradeName().trim())
					.param("sector", trim(request.sector()))
					.param("address", trim(request.address()))
					.param("city", trim(request.city()))
					.param("country", trim(request.country()))
					.param("phone", trim(request.phone()))
					.param("email", trim(request.email()))
					.param("website", trim(request.website()))
					.param("taxId", trim(request.taxId()))
					.param("currency", request.currency() != null && !request.currency().isBlank() ? request.currency().trim() : "FCFA")
					.param("ownerFirstName", trim(request.ownerFirstName()))
					.param("ownerLastName", request.ownerLastName().trim())
					.param("ownerEmail", trim(request.ownerEmail()))
					.param("ownerPhone", trim(request.ownerPhone()))
					.param("adminUsername", request.adminUsername().trim())
					.param("plan", request.plan() != null ? request.plan() : "STANDARD")
					.param("startsOn", date(request.subscriptionStartsOn()))
					.param("endsOn", date(request.subscriptionEndsOn()))
					.param("key", key)
					.param("createdBy", actorId())
					.update();
				this.db.jdbc()
					.sql("""
							insert into platform.company_branding (company_id, primary_color, secondary_color, button_color, link_color)
							values (:id, :primary, :secondary, :primary, :primary)""")
					.param("id", id)
					.param("primary", color(request.primaryColor(), "#2563EB"))
					.param("secondary", color(request.secondaryColor(), "#7C3AED"))
					.update();
				this.db.jdbc().sql("insert into platform.provisioning_job (company_id) values (:id)").param("id", id).update();
				this.audit.record("COMPANY_CREATED", id, "Entreprise " + code + " créée, provisionnement lancé");
			});
		}
		catch (DuplicateKeyException ex) {
			// Création concurrente avec la même clé : on renvoie celle qui a gagné.
			if (key != null) {
				Optional<UUID> winner = this.db.jdbc()
					.sql("select id from platform.company where idempotency_key = :key")
					.param("key", key)
					.query(UUID.class)
					.optional();
				if (winner.isPresent()) {
					return new Created(get(winner.get()), false);
				}
			}
			throw new ConflictException(ErrorCode.COMPANY_CODE_ALREADY_USED, ErrorCode.COMPANY_CODE_ALREADY_USED.defaultMessage());
		}
		this.provisioning.start(id);
		return new Created(get(id), true);
	}

	public Page<CompanyListItem> list(String q, String status, PageQuery query) {
		Pageable pageable = query.toPageable(SORTS);
		StringBuilder where = new StringBuilder(" where true");
		var params = new java.util.HashMap<String, Object>();
		if (SearchText.present(q)) {
			where.append(" and (lower(c.code) like :q or lower(c.trade_name) like :q or lower(c.legal_name) like :q"
					+ " or lower(coalesce(c.city, '')) like :q or lower(coalesce(c.owner_last_name, '')) like :q)");
			params.put("q", SearchText.pattern(q));
		}
		if (status != null && !status.isBlank()) {
			where.append(" and c.status = :status");
			params.put("status", status.trim().toUpperCase(Locale.ROOT));
		}
		var countSql = this.db.jdbc().sql("select count(*) from platform.company c" + where);
		for (var e : params.entrySet()) {
			countSql = countSql.param(e.getKey(), e.getValue());
		}
		long total = countSql.query(Long.class).single();
		String order = pageable.getSort().stream()
			.map(o -> o.getProperty() + (o.isAscending() ? " asc" : " desc"))
			.collect(Collectors.joining(", "));
		var listSql = this.db.jdbc()
			.sql("select c.* from platform.company c" + where + " order by " + order + " limit :limit offset :offset")
			.param("limit", pageable.getPageSize())
			.param("offset", pageable.getOffset());
		for (var e : params.entrySet()) {
			listSql = listSql.param(e.getKey(), e.getValue());
		}
		List<CompanyListItem> items = listSql.query(CompanyService::listItem).list();
		return new PageImpl<>(items, pageable, total);
	}

	public CompanyResponse get(UUID id) {
		return this.db.jdbc()
			.sql(SELECT + " where c.id = :id")
			.param("id", id)
			.query((rs, n) -> response(rs, steps(id)))
			.optional()
			.orElseThrow(CompanyService::notFound);
	}

	public CompanyResponse update(UUID id, UpdateCompanyRequest request) {
		get(id);
		checkDates(request.subscriptionStartsOn(), request.subscriptionEndsOn());
		this.db.jdbc()
			.sql("""
					update platform.company set legal_name = :legalName, trade_name = :tradeName, sector = :sector,
					    address = :address, city = :city, country = :country, phone = :phone, email = :email,
					    website = :website, tax_id = :taxId, currency = :currency, owner_first_name = :ownerFirstName,
					    owner_last_name = :ownerLastName, owner_email = :ownerEmail, owner_phone = :ownerPhone,
					    plan = coalesce(:plan, plan), subscription_starts_on = :startsOn, subscription_ends_on = :endsOn,
					    version = version + 1, updated_at = now()
					where id = :id""")
			.param("legalName", request.legalName().trim())
			.param("tradeName", request.tradeName().trim())
			.param("sector", trim(request.sector()))
			.param("address", trim(request.address()))
			.param("city", trim(request.city()))
			.param("country", trim(request.country()))
			.param("phone", trim(request.phone()))
			.param("email", trim(request.email()))
			.param("website", trim(request.website()))
			.param("taxId", trim(request.taxId()))
			.param("currency", request.currency() != null && !request.currency().isBlank() ? request.currency().trim() : "FCFA")
			.param("ownerFirstName", trim(request.ownerFirstName()))
			.param("ownerLastName", request.ownerLastName().trim())
			.param("ownerEmail", trim(request.ownerEmail()))
			.param("ownerPhone", trim(request.ownerPhone()))
			.param("plan", request.plan())
			.param("startsOn", date(request.subscriptionStartsOn()))
			.param("endsOn", date(request.subscriptionEndsOn()))
			.param("id", id)
			.update();
		this.directory.invalidate(id);
		this.audit.record("COMPANY_UPDATED", id, "Informations de l'entreprise modifiées");
		return get(id);
	}

	/**
	 * Suspend l'accès : les jetons en cours sont refusés dès la requête suivante (annuaire), les sessions
	 * sont révoquées, le pool de connexions est fermé. Les données ne sont jamais supprimées.
	 */
	public CompanyResponse suspend(UUID id, String reason) {
		CompanyResponse company = get(id);
		if (!"ACTIVE".equals(company.status())) {
			throw transition("Seule une entreprise active peut être suspendue.");
		}
		TenantContext.runAs(new TenantRef(id, company.code()), () -> this.tenantJdbc.update("""
				update refresh_token set revoked_at = now(), revoked_reason = 'ACCOUNT_DISABLED' where revoked_at is null""",
				Map.of()));
		setStatus(id, "SUSPENDED", reason.trim());
		this.pools.close(id);
		this.audit.record("COMPANY_SUSPENDED", id, "Motif : " + reason.trim());
		return get(id);
	}

	public CompanyResponse reactivate(UUID id) {
		CompanyResponse company = get(id);
		if (!"SUSPENDED".equals(company.status())) {
			throw transition("Seule une entreprise suspendue peut être réactivée.");
		}
		setStatus(id, "ACTIVE", null);
		this.audit.record("COMPANY_REACTIVATED", id, null);
		return get(id);
	}

	/** Relance le provisionnement d'une entreprise en échec (reprise à l'étape échouée). */
	public CompanyResponse retryProvisioning(UUID id) {
		CompanyResponse company = get(id);
		if (!"FAILED".equals(company.status())) {
			throw transition("Le provisionnement ne peut être relancé que pour une entreprise en échec.");
		}
		this.audit.record("COMPANY_PROVISIONING_RETRY", id, null);
		this.provisioning.start(id);
		return get(id);
	}

	/**
	 * Lien d'activation (ou de réinitialisation) de l'administrateur de l'entreprise, montré une seule fois.
	 * Les liens précédents sont invalidés. Le jeton n'est ni stocké en clair ni journalisé.
	 */
	public ActivationLinkResponse adminAccessLink(UUID id) {
		CompanyResponse company = get(id);
		if (!"ACTIVE".equals(company.status())) {
			throw transition("L'entreprise doit être active pour générer un lien d'accès.");
		}
		TenantRef tenant = new TenantRef(id, company.code());
		AccountAccessService.AccessLink link = TenantContext.callAs(tenant, () -> {
			UUID userId = this.accounts.findByUsername(company.adminUsername())
				.orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND,
						"Administrateur « " + company.adminUsername() + " » introuvable dans la base de l'entreprise."))
				.id();
			return this.accessLinks.issue(userId, AccountAccessService.Purpose.ACTIVATION);
		});
		this.audit.record("COMPANY_ADMIN_ACCESS_LINK", id, "Lien d'accès créé pour " + company.adminUsername());
		return new ActivationLinkResponse(company.code(), company.adminUsername(), link.token(), link.expiresAt());
	}

	public PlatformStats stats() {
		Map<String, Long> byStatus = this.db.jdbc()
			.sql("select status, count(*) as n from platform.company group by status")
			.query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		LocalDate today = LocalDate.now(this.clock);
		long expiring = this.db.jdbc()
			.sql("select count(*) from platform.company where status = 'ACTIVE' and subscription_ends_on between :today and :limit")
			.param("today", Date.valueOf(today))
			.param("limit", Date.valueOf(today.plusDays(30)))
			.query(Long.class)
			.single();
		List<CompanyListItem> recent = this.db.jdbc()
			.sql("select c.* from platform.company c order by c.created_at desc limit 5")
			.query(CompanyService::listItem)
			.list();
		long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
		return new PlatformStats(total, byStatus.getOrDefault("ACTIVE", 0L), byStatus.getOrDefault("SUSPENDED", 0L),
				byStatus.getOrDefault("PROVISIONING", 0L) + byStatus.getOrDefault("PENDING", 0L),
				byStatus.getOrDefault("FAILED", 0L), expiring, recent);
	}

	public Page<AuditEntry> auditLog(UUID companyId, PageQuery query) {
		Pageable pageable = query.toPageable(new SortWhitelist(Map.of("occurredAt", "a.occurred_at"),
				Sort.by(Sort.Direction.DESC, "a.occurred_at"), List.of("a.id")));
		String where = companyId != null ? " where a.company_id = :company" : "";
		var count = this.db.jdbc().sql("select count(*) from platform.platform_audit_log a" + where);
		var list = this.db.jdbc()
			.sql("select a.*, c.code as company_code from platform.platform_audit_log a left join platform.company c on c.id = a.company_id"
					+ where + " order by a.occurred_at " + (pageable.getSort().getOrderFor("a.occurred_at") != null
							&& pageable.getSort().getOrderFor("a.occurred_at").isAscending() ? "asc" : "desc")
					+ ", a.id limit :limit offset :offset")
			.param("limit", pageable.getPageSize())
			.param("offset", pageable.getOffset());
		if (companyId != null) {
			count = count.param("company", companyId);
			list = list.param("company", companyId);
		}
		long total = count.query(Long.class).single();
		List<AuditEntry> items = list.query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class),
				rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_name"), rs.getString("action"),
				rs.getObject("company_id", UUID.class), rs.getString("company_code"), rs.getString("details")))
			.list();
		return new PageImpl<>(items, pageable, total);
	}

	// --- Outils ---------------------------------------------------------------------------------------

	private void setStatus(UUID id, String status, String reason) {
		this.db.jdbc()
			.sql("update platform.company set status = :status, status_reason = :reason, version = version + 1, updated_at = now() where id = :id")
			.param("status", status)
			.param("reason", reason)
			.param("id", id)
			.update();
		this.directory.invalidate(id);
	}

	private List<ProvisioningStepInfo> steps(UUID companyId) {
		return this.db.jdbc()
			.sql("""
					select s.step, s.status, s.message, s.attempt, s.occurred_at
					from platform.provisioning_step s join platform.provisioning_job j on j.id = s.job_id
					where j.company_id = :company order by s.occurred_at, s.id""")
			.param("company", companyId)
			.query((rs, n) -> new ProvisioningStepInfo(rs.getString("step"), rs.getString("status"), rs.getString("message"),
					rs.getInt("attempt"), rs.getTimestamp("occurred_at").toInstant()))
			.list();
	}

	private static CompanyResponse response(ResultSet rs, List<ProvisioningStepInfo> steps) throws SQLException {
		DatabaseInfo database = rs.getString("database_name") != null
				? new DatabaseInfo(rs.getString("database_name"), rs.getString("database_mode")) : null;
		ProvisioningInfo provisioning = rs.getString("job_status") != null
				? new ProvisioningInfo(rs.getString("job_status"), rs.getString("current_step"), rs.getInt("attempts"),
						rs.getString("last_error"), instant(rs, "job_started_at"), instant(rs, "job_finished_at"), steps)
				: null;
		return new CompanyResponse(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("code"),
				rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("sector"), rs.getString("address"),
				rs.getString("city"), rs.getString("country"), rs.getString("phone"), rs.getString("email"),
				rs.getString("website"), rs.getString("tax_id"), rs.getString("currency"), rs.getString("owner_first_name"),
				rs.getString("owner_last_name"), rs.getString("owner_email"), rs.getString("owner_phone"),
				rs.getString("admin_username"), rs.getString("plan"), localDate(rs, "subscription_starts_on"),
				localDate(rs, "subscription_ends_on"), rs.getString("status"), rs.getString("status_reason"),
				instant(rs, "created_at"), instant(rs, "updated_at"), database, provisioning);
	}

	private static CompanyListItem listItem(ResultSet rs, int row) throws SQLException {
		return new CompanyListItem(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("code"),
				rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("city"), rs.getString("plan"),
				rs.getString("status"), localDate(rs, "subscription_ends_on"), instant(rs, "created_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value != null ? value.toInstant() : null;
	}

	private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
		Date value = rs.getDate(column);
		return value != null ? value.toLocalDate() : null;
	}

	private static Date date(LocalDate value) {
		return value != null ? Date.valueOf(value) : null;
	}

	private static String trim(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private static String color(String value, String fallback) {
		return value != null && !value.isBlank() ? value.trim().toUpperCase(Locale.ROOT) : fallback;
	}

	private static String normalizeKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		String trimmed = key.trim();
		if (trimmed.length() > 100 || !trimmed.matches("[A-Za-z0-9._:-]+")) {
			throw new InvalidRequestException("En-tête Idempotency-Key invalide (100 caractères au plus : lettres, chiffres, . _ : -).");
		}
		return trimmed;
	}

	private static void checkDates(LocalDate startsOn, LocalDate endsOn) {
		if (startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) {
			throw new InvalidRequestException("La fin d'abonnement doit suivre son début.");
		}
	}

	private static UUID actorId() {
		var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof com.geststock.platform.admin.PlatformAuthentication platform) {
			return platform.getPrincipal().id();
		}
		return null;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.COMPANY_NOT_FOUND, ErrorCode.COMPANY_NOT_FOUND.defaultMessage());
	}

	private static ConflictException transition(String message) {
		return new ConflictException(ErrorCode.COMPANY_INVALID_TRANSITION, message);
	}

	/**
	 * Résultat d'une création.
	 *
	 * @param company entreprise
	 * @param created {@code false} si la clé d'idempotence désignait une entreprise existante
	 */
	public record Created(CompanyResponse company, boolean created) {
	}

}
