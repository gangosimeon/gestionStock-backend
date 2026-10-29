package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Jeton d'accès d'une liaison (lot 2, étape G5 ; v13-D5, v14-D3, v14-D4) : contenu exact (en-tête
 * {@code typ=at+jwt}, {@code iss}, {@code aud}, {@code typ}, {@code sub}, {@code client_id}, {@code scope},
 * {@code iat}, {@code exp} à 15 min, {@code jti}), vérification stricte, rotation des clés, tolérance d'horloge,
 * aucune clé ni aucun jeton dans les journaux et les messages.
 */
class IntegrationTokenServiceTest {

	private static final String OLD_SECRET = "geststock-test-link-token-key-0-previous-not-a-secret";

	private static final String CURRENT_SECRET = "geststock-test-link-token-key-1-current-not-a-secret";

	/** Secret des jetons utilisateurs (forme brute, comme GESTSTOCK_JWT_SECRET). */
	private static final String USER_SECRET = "geststock-test-user-jwt-secret-of-at-least-32-bytes";

	private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");

	private static final UUID LINK = UUID.fromString("0193f0c4-9a2b-7c3d-8e4f-5a6b7c8d9e0f");

	private static final String CLIENT = "jcmb-test";

	private ListAppender<ILoggingEvent> logs;

	private Logger root;

	private Level rootLevel;

	@BeforeEach
	void captureLogs() {
		this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		this.rootLevel = this.root.getLevel();
		this.root.setLevel(Level.TRACE);
		this.logs = new ListAppender<>();
		this.logs.start();
		this.root.addAppender(this.logs);
	}

	@AfterEach
	void releaseLogs() {
		this.root.detachAppender(this.logs);
		this.root.setLevel(this.rootLevel);
	}

	// --- Contenu ------------------------------------------------------------------------------------------

	@Test
	void anIssuedTokenCarriesExactlyTheExpectedHeaderAndClaimsAndIsVerified() throws Exception {
		IntegrationTokenService service = service("test-1", Map.of("test-1", CURRENT_SECRET), NOW);

		LinkAccessToken issued = service.issue(LINK, CLIENT, Set.of("orders:write", "catalog:read"));

		assertThat(issued.expiresInSeconds()).isEqualTo(900);
		assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(issued.toString()).doesNotContain(issued.value());

		SignedJWT jwt = SignedJWT.parse(issued.value());
		JWSHeader header = jwt.getHeader();
		assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
		assertThat(header.getType()).isEqualTo(new JOSEObjectType("at+jwt"));
		assertThat(header.getKeyID()).isEqualTo("test-1");
		assertThat(header.toJSONObject()).containsOnlyKeys("alg", "typ", "kid");

		JWTClaimsSet claims = jwt.getJWTClaimsSet();
		assertThat(claims.getClaims()).containsOnlyKeys("iss", "aud", "sub", "typ", "client_id", "scope", "iat", "exp", "jti");
		assertThat(claims.getIssuer()).isEqualTo("geststock-integration");
		assertThat(claims.getAudience()).containsExactly("geststock-integration");
		assertThat(claims.getSubject()).isEqualTo(LINK.toString());
		assertThat(claims.getStringClaim("typ")).isEqualTo("integration");
		assertThat(claims.getStringClaim("client_id")).isEqualTo(CLIENT);
		assertThat(claims.getStringClaim("scope")).isEqualTo("catalog:read orders:write");
		assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
		assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(UUID.fromString(claims.getJWTID())).isNotNull();

		LinkTokenClaims verified = service.verify(issued.value());
		assertThat(verified.linkId()).isEqualTo(LINK);
		assertThat(verified.clientId()).isEqualTo(CLIENT);
		assertThat(verified.scopes()).containsExactlyInAnyOrder("catalog:read", "orders:write");
		assertThat(verified.tokenId()).isEqualTo(claims.getJWTID());
		assertThat(verified.issuedAt()).isEqualTo(NOW);
		assertThat(verified.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(verified.keyId()).isEqualTo("test-1");
	}

	@Test
	void eachTokenHasItsOwnIdentifier() {
		IntegrationTokenService service = service("test-1", Map.of("test-1", CURRENT_SECRET), NOW);

		assertThat(service.verify(service.issue(LINK, CLIENT, Set.of("catalog:read")).value()).tokenId())
			.isNotEqualTo(service.verify(service.issue(LINK, CLIENT, Set.of("catalog:read")).value()).tokenId());
	}

	@Test
	void issuingRequiresALinkAClientAndKnownLookingScopes() {
		IntegrationTokenService service = service("test-1", Map.of("test-1", CURRENT_SECRET), NOW);

		assertThatThrownBy(() -> service.issue(null, CLIENT, Set.of("catalog:read"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.issue(LINK, "Bad Client", Set.of("catalog:read"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.issue(LINK, CLIENT, Set.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.issue(LINK, CLIENT, Set.of("catalog read"))).isInstanceOf(IllegalArgumentException.class);
	}

	// --- Refus ----------------------------------------------------------------------------------------------

	@Test
	void aTokenSignedWithTheUserKeyIsRefused() throws Exception {
		assertRefused(forge(header(JWSAlgorithm.HS256, "at+jwt", "test-1"), validClaims(), USER_SECRET),
				InvalidLinkTokenException.Reason.SIGNATURE);
	}

	@Test
	void anUnknownKeyIdOrNoKeyIdIsRefused() throws Exception {
		assertRefused(forge(header(JWSAlgorithm.HS256, "at+jwt", "inconnue"), validClaims(), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.UNKNOWN_KEY);
		assertRefused(forge(header(JWSAlgorithm.HS256, "at+jwt", null), validClaims(), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.UNKNOWN_KEY);
	}

	@Test
	void algNoneAndUnexpectedAlgorithmsAreRefused() throws Exception {
		String none = new PlainJWT(validClaims().build()).serialize();
		assertThatThrownBy(() -> current().verify(none)).isInstanceOf(InvalidLinkTokenException.class);
		// Même clé, autre algorithme HMAC : refusé avant toute vérification de signature.
		assertRefused(forge(header(JWSAlgorithm.HS384, "at+jwt", "test-1"), validClaims(), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.ALGORITHM);
	}

	@Test
	void aWrongIssuerAudienceOrTypeIsRefused() throws Exception {
		assertRefused(forge(validHeader(), validClaims().issuer("geststock"), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.ISSUER);
		assertRefused(forge(validHeader(), validClaims().audience("geststock-integration-v1"), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.AUDIENCE);
		assertRefused(forge(validHeader(), validClaims().audience(List.of("geststock-integration", "autre")), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.AUDIENCE);
		assertRefused(forge(validHeader(), validClaims().claim("typ", "tenant"), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.TOKEN_TYPE);
		assertRefused(forge(header(JWSAlgorithm.HS256, "JWT", "test-1"), validClaims(), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.HEADER_TYPE);
		assertRefused(forge(header(JWSAlgorithm.HS256, null, "test-1"), validClaims(), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.HEADER_TYPE);
	}

	@Test
	void expirationIsCheckedWithAConfigurableClockSkew() {
		String token = service("test-1", Map.of("test-1", CURRENT_SECRET), NOW).issue(LINK, CLIENT, Set.of("catalog:read"))
			.value();
		Instant expiry = NOW.plus(Duration.ofMinutes(15));

		assertThat(service("test-1", Map.of("test-1", CURRENT_SECRET), expiry.plusSeconds(29)).verify(token).linkId())
			.as("dans la tolérance de 30 s").isEqualTo(LINK);
		assertThatThrownBy(() -> service("test-1", Map.of("test-1", CURRENT_SECRET), expiry.plusSeconds(31)).verify(token))
			.isInstanceOfSatisfying(InvalidLinkTokenException.class,
					e -> assertThat(e.reason()).isEqualTo(InvalidLinkTokenException.Reason.EXPIRED));
		assertThat(service("test-1", Map.of("test-1", CURRENT_SECRET), expiry.plusSeconds(31), Duration.ofMinutes(1))
			.verify(token).linkId()).as("tolérance portée à 1 min").isEqualTo(LINK);
	}

	@Test
	void aTokenFromTheFutureOrLivingTooLongIsRefused() throws Exception {
		assertRefused(forge(validHeader(), validClaims().issueTime(Date.from(NOW.plusSeconds(120)))
			.expirationTime(Date.from(NOW.plusSeconds(120 + 900))), CURRENT_SECRET), InvalidLinkTokenException.Reason.NOT_YET_VALID);
		assertRefused(forge(validHeader(), validClaims().expirationTime(Date.from(NOW.plus(Duration.ofHours(1)))), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.LIFETIME);
	}

	@Test
	void everyMandatoryClaimIsRequired() throws Exception {
		for (String claim : List.of("iss", "aud", "sub", "typ", "client_id", "scope", "iat", "exp", "jti")) {
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder(validClaims().build()).claim(claim, null);
			assertRefused(forge(validHeader(), claims, CURRENT_SECRET), InvalidLinkTokenException.Reason.MISSING_CLAIM);
		}
		assertRefused(forge(validHeader(), validClaims().subject("pas-un-uuid"), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.INVALID_CLAIM);
		assertRefused(forge(validHeader(), validClaims().claim("scope", " "), CURRENT_SECRET),
				InvalidLinkTokenException.Reason.INVALID_CLAIM);
	}

	@Test
	void unreadableTokensAreRefused() {
		for (String garbage : java.util.Arrays.asList(null, "", "abc", "a.b.c", "eyJhbGciOiJIUzI1NiJ9..")) {
			assertThatThrownBy(() -> current().verify(garbage)).isInstanceOf(InvalidLinkTokenException.class);
		}
	}

	// --- Rotation ---------------------------------------------------------------------------------------------

	@Test
	void aTokenSignedByAPreviousKeyStillListedIsAcceptedThenRefusedOnceTheKeyIsRemoved() {
		String signedByOldKey = service("test-0", Map.of("test-0", OLD_SECRET), NOW).issue(LINK, CLIENT, Set.of("catalog:read"))
			.value();

		IntegrationTokenService rotated = service("test-1", Map.of("test-0", OLD_SECRET, "test-1", CURRENT_SECRET), NOW);
		assertThat(rotated.verify(signedByOldKey).keyId()).isEqualTo("test-0");
		assertThat(SignedJWTs.keyId(rotated.issue(LINK, CLIENT, Set.of("catalog:read")).value())).as("la clé courante signe")
			.isEqualTo("test-1");

		IntegrationTokenService withdrawn = service("test-1", Map.of("test-1", CURRENT_SECRET), NOW);
		assertThatThrownBy(() -> withdrawn.verify(signedByOldKey)).isInstanceOfSatisfying(InvalidLinkTokenException.class,
				e -> assertThat(e.reason()).isEqualTo(InvalidLinkTokenException.Reason.UNKNOWN_KEY));
	}

	// --- Configuration ------------------------------------------------------------------------------------

	@Test
	void anInvalidConfigurationIsRefusedWithoutRevealingAnyKey() {
		String shortKey = base64("trop-courte");
		assertThatThrownBy(() -> new IntegrationTokenService(properties(true, null, Map.of()), clock(NOW)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("current-kid");
		assertThatThrownBy(() -> new IntegrationTokenService(properties(true, "k1", Map.of("k1", shortKey)), clock(NOW)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 octets").hasMessageNotContaining(shortKey);
	}

	@Test
	void disabledLinksIssueNothing() {
		IntegrationTokenService disabled = new IntegrationTokenService(properties(false, null, Map.of()), clock(NOW));

		assertThatThrownBy(() -> disabled.issue(LINK, CLIENT, Set.of("catalog:read"))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> disabled.verify("a.b.c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void noKeyAndNoTokenAppearInLogsOrMessages() throws Exception {
		List<String> messages = new ArrayList<>();
		Consumer<Throwable> collect = e -> messages.add(String.valueOf(e.getMessage()));
		IntegrationTokenService service = current();
		String token = service.issue(LINK, CLIENT, Set.of("catalog:read")).value();
		for (String refused : List.of(forge(header(JWSAlgorithm.HS256, "at+jwt", "test-1"), validClaims(), USER_SECRET),
				forge(header(JWSAlgorithm.HS256, "at+jwt", "inconnue"), validClaims(), CURRENT_SECRET),
				forge(validHeader(), validClaims().issuer("geststock"), CURRENT_SECRET), token + "x")) {
			try {
				service.verify(refused);
			}
			catch (InvalidLinkTokenException ex) {
				collect.accept(ex);
			}
		}
		try {
			new IntegrationTokenService(properties(true, "k1", Map.of("k1", base64("trop-courte"))), clock(NOW));
		}
		catch (IllegalStateException ex) {
			collect.accept(ex);
		}
		this.logs.list.forEach(event -> messages.add(event.getFormattedMessage()));

		assertThat(messages).isNotEmpty().allSatisfy(message -> assertThat(message)
			.doesNotContain(CURRENT_SECRET, base64(CURRENT_SECRET), OLD_SECRET, base64(OLD_SECRET), USER_SECRET,
					base64("trop-courte"), token));
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private IntegrationTokenService current() {
		return service("test-1", Map.of("test-0", OLD_SECRET, "test-1", CURRENT_SECRET), NOW);
	}

	private static IntegrationTokenService service(String currentKid, Map<String, String> rawSecrets, Instant now) {
		return service(currentKid, rawSecrets, now, Duration.ofSeconds(30));
	}

	private static IntegrationTokenService service(String currentKid, Map<String, String> rawSecrets, Instant now,
			Duration clockSkew) {
		Map<String, String> keys = new java.util.LinkedHashMap<>();
		rawSecrets.forEach((kid, secret) -> keys.put(kid, base64(secret)));
		return new IntegrationTokenService(new IntegrationLinkProperties(true,
				new IntegrationLinkProperties.Token(currentKid, keys, clockSkew)), clock(now));
	}

	private static IntegrationLinkProperties properties(boolean enabled, String currentKid, Map<String, String> keys) {
		return new IntegrationLinkProperties(enabled, new IntegrationLinkProperties.Token(currentKid, keys, Duration.ofSeconds(30)));
	}

	private void assertRefused(String token, InvalidLinkTokenException.Reason reason) {
		assertThatThrownBy(() -> current().verify(token)).as(reason.name())
			.isInstanceOfSatisfying(InvalidLinkTokenException.class, e -> assertThat(e.reason()).isEqualTo(reason));
	}

	private static JWSHeader validHeader() {
		return header(JWSAlgorithm.HS256, "at+jwt", "test-1");
	}

	private static JWSHeader header(JWSAlgorithm algorithm, String type, String kid) {
		JWSHeader.Builder builder = new JWSHeader.Builder(algorithm);
		if (type != null) {
			builder.type(new JOSEObjectType(type));
		}
		if (kid != null) {
			builder.keyID(kid);
		}
		return builder.build();
	}

	private static JWTClaimsSet.Builder validClaims() {
		return new JWTClaimsSet.Builder().issuer("geststock-integration").audience("geststock-integration")
			.subject(LINK.toString()).claim("typ", "integration").claim("client_id", CLIENT).claim("scope", "catalog:read")
			.issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(900))).jwtID(UUID.randomUUID().toString());
	}

	private static String forge(JWSHeader header, JWTClaimsSet.Builder claims, String rawSecret) throws Exception {
		SignedJWT jwt = new SignedJWT(header, claims.build());
		jwt.sign(new MACSigner(rawSecret.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static Clock clock(Instant now) {
		return Clock.fixed(now, ZoneOffset.UTC);
	}

	private static final class SignedJWTs {

		static String keyId(String token) {
			try {
				return SignedJWT.parse(token).getHeader().getKeyID();
			}
			catch (java.text.ParseException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
