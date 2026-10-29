package com.geststock.integration.link;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.Header;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Jeton d'accès d'une liaison (lot 2, étape G5 ; v13-D5, v14-D3, v14-D4) : émission et vérification.
 *
 * <p>Format : JWS compact HS256, en-tête {@code {alg: HS256, typ: at+jwt, kid}} (RFC 9068) ; claims {@code iss} et
 * {@code aud} = {@code geststock-integration}, {@code sub} = identifiant de la liaison, {@code typ} =
 * {@code integration}, {@code client_id}, {@code scope} (portées séparées par des espaces), {@code iat},
 * {@code exp} (15 minutes), {@code jti}. Aucune donnée personnelle ; pas d'entreprise : elle est relue depuis la
 * liaison à chaque appel (étape G7).
 *
 * <p>Clés propres aux liaisons ({@link IntegrationLinkProperties.Token}), distinctes de la clé des jetons
 * utilisateurs, avec {@code kid} et rotation. Signature et vérification par des instances Nimbus privées : aucun
 * bean {@code JwtEncoder} ni {@code JwtDecoder} n'est ajouté, la chaîne principale garde les siens.
 *
 * <p>Vérification stricte, dans l'ordre : format, algorithme (HS256 seul), en-tête {@code typ}, {@code kid} connu,
 * signature, présence des claims, valeurs ({@code iss}, {@code aud}, {@code typ}, forme des claims), durée de vie,
 * horloge (tolérance configurable). Aucun message ni journal ne contient de jeton ni de clé.
 */
@Component
public class IntegrationTokenService {

	private static final Logger log = LoggerFactory.getLogger(IntegrationTokenService.class);

	public static final String ISSUER = "geststock-integration";

	public static final String AUDIENCE = "geststock-integration";

	/** Valeur du claim {@code typ} ; les jetons utilisateurs portent {@code tenant} ou {@code platform}. */
	public static final String TOKEN_TYPE = "integration";

	/** En-tête {@code typ} des jetons d'accès JWT (RFC 9068). */
	public static final JOSEObjectType HEADER_TYPE = new JOSEObjectType("at+jwt");

	/** Durée de vie d'un jeton de liaison (v13-D5). */
	public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

	static final String TYPE_CLAIM = "typ";

	static final String CLIENT_ID_CLAIM = "client_id";

	static final String SCOPE_CLAIM = "scope";

	private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;

	private static final List<String> MANDATORY_CLAIMS = List.of("iss", "aud", "sub", TYPE_CLAIM, CLIENT_ID_CLAIM,
			SCOPE_CLAIM, "iat", "exp", "jti");

	private static final Pattern CLIENT_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,62}[a-z0-9]$");

	private static final Pattern SCOPE = Pattern.compile("^[a-z][a-z-]*:[a-z][a-z-]*$");

	private final boolean enabled;

	private final String currentKid;

	private final Map<String, byte[]> keys;

	private final Duration clockSkew;

	private final Clock clock;

	IntegrationTokenService(IntegrationLinkProperties properties, Clock clock) {
		IntegrationLinkProperties.Token token = properties.token();
		this.enabled = properties.enabled();
		this.clock = clock;
		this.clockSkew = token.clockSkew();
		if (!this.enabled) {
			this.currentKid = null;
			this.keys = Map.of();
			return;
		}
		List<String> problems = token.problems();
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Configuration des jetons de liaison refusée : " + String.join(" ; ", problems));
		}
		this.currentKid = token.currentKid();
		this.keys = token.secrets();
		log.info("Jetons de liaison : clé courante « {} », {} clé(s) acceptée(s) en vérification, tolérance d'horloge {}.",
				this.currentKid, this.keys.size(), this.clockSkew);
	}

	/**
	 * Émet un jeton de 15 minutes pour la liaison, signé par la clé courante.
	 *
	 * @param linkId   liaison ({@code sub})
	 * @param clientId client JeCréeMaBoutique ({@code client_id})
	 * @param scopes   portées accordées (au moins une)
	 */
	public LinkAccessToken issue(UUID linkId, String clientId, Set<String> scopes) {
		requireEnabled();
		if (linkId == null) {
			throw new IllegalArgumentException("Liaison absente.");
		}
		if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
			throw new IllegalArgumentException("Identifiant de client mal formé.");
		}
		if (scopes == null || scopes.isEmpty() || !scopes.stream().allMatch(s -> s != null && SCOPE.matcher(s).matches())) {
			throw new IllegalArgumentException("Portées absentes ou mal formées.");
		}
		Instant issuedAt = Instant.now(this.clock).truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(TOKEN_TTL);
		JWSHeader header = new JWSHeader.Builder(ALGORITHM).type(HEADER_TYPE).keyID(this.currentKid).build();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(linkId.toString())
			.claim(TYPE_CLAIM, TOKEN_TYPE).claim(CLIENT_ID_CLAIM, clientId).claim(SCOPE_CLAIM, String.join(" ", new TreeSet<>(scopes)))
			.issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt)).jwtID(UUID.randomUUID().toString())
			.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		try {
			jwt.sign(new MACSigner(this.keys.get(this.currentKid)));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Signature du jeton de liaison impossible (clé « " + this.currentKid + " »).");
		}
		return new LinkAccessToken(jwt.serialize(), expiresAt, TOKEN_TTL.toSeconds());
	}

	/**
	 * Vérifie un jeton de liaison et en renvoie le contenu.
	 *
	 * @throws InvalidLinkTokenException jeton refusé ; la raison n'expose ni le jeton ni une clé
	 */
	public LinkTokenClaims verify(String token) {
		requireEnabled();
		if (token == null || token.isBlank()) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MALFORMED);
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MALFORMED);
		}
		Header rawHeader = parseHeader(parts[0]);
		if (!ALGORITHM.equals(rawHeader.getAlgorithm())) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.ALGORITHM);
		}
		SignedJWT jwt;
		try {
			jwt = SignedJWT.parse(token);
		}
		catch (ParseException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MALFORMED);
		}
		JWSHeader header = jwt.getHeader();
		if (!HEADER_TYPE.equals(header.getType())) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.HEADER_TYPE);
		}
		String kid = header.getKeyID();
		byte[] secret = kid != null ? this.keys.get(kid) : null;
		if (secret == null) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.UNKNOWN_KEY);
		}
		try {
			if (!jwt.verify(new MACVerifier(secret))) {
				throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.SIGNATURE);
			}
		}
		catch (JOSEException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.SIGNATURE);
		}
		return claimsOf(jwt, kid);
	}

	private LinkTokenClaims claimsOf(SignedJWT jwt, String kid) {
		JWTClaimsSet claims;
		try {
			claims = jwt.getJWTClaimsSet();
		}
		catch (ParseException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MALFORMED);
		}
		for (String name : MANDATORY_CLAIMS) {
			Object value = claims.getClaim(name);
			if (value == null || (value instanceof List<?> list && list.isEmpty())) {
				throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MISSING_CLAIM);
			}
		}
		String issuer;
		String type;
		String clientId;
		String scope;
		try {
			issuer = claims.getStringClaim("iss");
			type = claims.getStringClaim(TYPE_CLAIM);
			clientId = claims.getStringClaim(CLIENT_ID_CLAIM);
			scope = claims.getStringClaim(SCOPE_CLAIM);
		}
		catch (ParseException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.INVALID_CLAIM);
		}
		if (!ISSUER.equals(issuer)) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.ISSUER);
		}
		if (!List.of(AUDIENCE).equals(claims.getAudience())) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.AUDIENCE);
		}
		if (!TOKEN_TYPE.equals(type)) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.TOKEN_TYPE);
		}
		// Nimbus renvoie null pour un claim du mauvais type : refusé comme mal formé.
		String subject = claims.getSubject();
		String tokenId = claims.getJWTID();
		Date issueTime = claims.getIssueTime();
		Date expirationTime = claims.getExpirationTime();
		if (subject == null || tokenId == null || tokenId.isBlank() || issueTime == null || expirationTime == null
				|| clientId == null || scope == null) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.INVALID_CLAIM);
		}
		UUID linkId;
		try {
			linkId = UUID.fromString(subject);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.INVALID_CLAIM);
		}
		Set<String> scopes = new LinkedHashSet<>();
		for (String value : scope.trim().split(" +")) {
			if (!SCOPE.matcher(value).matches()) {
				throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.INVALID_CLAIM);
			}
			scopes.add(value);
		}
		if (scopes.isEmpty() || !CLIENT_ID.matcher(clientId).matches()) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.INVALID_CLAIM);
		}
		Instant issuedAt = issueTime.toInstant();
		Instant expiresAt = expirationTime.toInstant();
		if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(TOKEN_TTL) > 0) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.LIFETIME);
		}
		Instant now = Instant.now(this.clock);
		if (issuedAt.isAfter(now.plus(this.clockSkew))) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.NOT_YET_VALID);
		}
		if (!now.isBefore(expiresAt.plus(this.clockSkew))) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.EXPIRED);
		}
		return new LinkTokenClaims(linkId, clientId, scopes, tokenId, issuedAt, expiresAt, kid);
	}

	private static Header parseHeader(String encoded) {
		try {
			return Header.parse(new Base64URL(encoded));
		}
		catch (ParseException ex) {
			throw new InvalidLinkTokenException(InvalidLinkTokenException.Reason.MALFORMED);
		}
	}

	private void requireEnabled() {
		if (!this.enabled) {
			throw new IllegalStateException("Liaisons désactivées (geststock.integration.links.enabled=false).");
		}
	}

}
