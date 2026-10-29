package com.geststock.integration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code pageToken} d'instantané (contrat P4, v9-D2) : opaque pour le client, <b>signé</b> (HMAC-SHA256), non
 * chiffré, encodé en base64url sans remplissage, 512 caractères au plus.
 *
 * <p>Format 1 (octets) : numéro de format (1) · longueur du {@code kid} (1) · {@code kid} ASCII (1 à
 * {@value #MAX_KID_LENGTH}) · identifiant de liaison (16) · {@code asOfSeq} (8) · heure de la première page,
 * secondes depuis l'époque (8) · section (1) · dernier identifiant servi dans la section (16) · HMAC-SHA256 de
 * tout ce qui précède (32). Soit 83 octets plus le {@code kid} : 154 caractères au plus.
 *
 * <p>Contrôles, dans cet ordre : format → {@code kid} connu → signature (comparaison en temps constant,
 * {@link MessageDigest#isEqual}) → liaison identique à l'appelant → expiration. Tout échec avant l'expiration :
 * {@link InvalidPageTokenException} (400 {@code INVALID_PAGE_TOKEN}), sans préciser la cause au client ; jeton
 * émis il y a plus de 24 heures (heure de la première page, horloge de l'application) :
 * {@link SnapshotExpiredException} (410 {@code SNAPSHOT_EXPIRED}). Aucune clé n'apparaît dans les journaux ni dans
 * les messages.
 *
 * <p><b>Rotation des clés</b> (sur 24 heures, durée de validité d'un jeton) :
 * <ol>
 * <li>ajouter la nouvelle clé dans {@code geststock.integration.page-token.keys}, sans changer
 * {@code current-kid}, et déployer : elle vérifie déjà, elle ne signe pas encore ;</li>
 * <li>passer {@code current-kid} sur la nouvelle clé et déployer : elle signe ; l'ancienne, toujours listée,
 * vérifie les jetons en cours ;</li>
 * <li>24 heures plus tard au moins, retirer l'ancienne clé : ses jetons sont de toute façon expirés.</li>
 * </ol>
 * Une clé compromise se retire immédiatement : les instantanés en cours échouent en 400 et recommencent.
 */
@Component
class PageTokenCodec {

	private static final Logger log = LoggerFactory.getLogger(PageTokenCodec.class);

	static final int MAX_KID_LENGTH = 32;

	static final int MAX_TOKEN_LENGTH = 512;

	static final Duration VALIDITY = Duration.ofHours(24);

	private static final byte FORMAT = 1;

	private static final String ALGORITHM = "HmacSHA256";

	private static final int MAC_BYTES = 32;

	/** Liaison, asOfSeq, heure, section, dernier identifiant. */
	private static final int BODY_BYTES = 16 + 8 + 8 + 1 + 16;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private static final SnapshotSection[] SECTIONS = SnapshotSection.values();

	private final Map<String, byte[]> secrets;

	private final String currentKid;

	private final Clock clock;

	PageTokenCodec(PageTokenProperties properties, Clock clock) {
		this.secrets = properties.secrets();
		this.currentKid = properties.currentKid() == null || properties.currentKid().isBlank() ? null
				: properties.currentKid();
		if (this.currentKid != null && !this.secrets.containsKey(this.currentKid)) {
			throw new IllegalStateException("geststock.integration.page-token.keys ne contient pas la clé courante « "
					+ this.currentKid + " ».");
		}
		this.clock = clock;
	}

	/** Jeton signé par la clé courante. */
	String encode(PageToken token) {
		if (this.currentKid == null) {
			throw new IllegalStateException("Aucune clé courante de signature des jetons d'instantané : définir "
					+ "geststock.integration.page-token.current-kid et sa clé.");
		}
		byte[] kid = this.currentKid.getBytes(StandardCharsets.US_ASCII);
		ByteBuffer buffer = ByteBuffer.allocate(2 + kid.length + BODY_BYTES + MAC_BYTES);
		buffer.put(FORMAT).put((byte) kid.length).put(kid);
		putUuid(buffer, token.link().id());
		buffer.putLong(token.asOfSeq()).putLong(token.issuedAt().getEpochSecond()).put((byte) token.section().ordinal());
		putUuid(buffer, token.lastId());
		buffer.put(mac(this.secrets.get(this.currentKid), buffer.array(), buffer.position()));
		return ENCODER.encodeToString(buffer.array());
	}

	/**
	 * Jeton vérifié, pour la liaison qui le présente.
	 *
	 * @throws InvalidPageTokenException illisible, altéré, {@code kid} inconnu ou autre liaison
	 * @throws SnapshotExpiredException  émis il y a plus de 24 heures
	 */
	PageToken decode(String token, LinkRef link) {
		byte[] bytes = readable(token);
		int kidLength = bytes[1] & 0xFF;
		int signedLength = 2 + kidLength + BODY_BYTES;
		String kid = new String(bytes, 2, kidLength, StandardCharsets.US_ASCII);
		byte[] secret = this.secrets.get(kid);
		if (secret == null) {
			throw refused("clé inconnue");
		}
		if (!MessageDigest.isEqual(mac(secret, bytes, signedLength), Arrays.copyOfRange(bytes, signedLength, bytes.length))) {
			throw refused("signature invalide");
		}
		ByteBuffer body = ByteBuffer.wrap(bytes, 2 + kidLength, BODY_BYTES);
		UUID linkId = new UUID(body.getLong(), body.getLong());
		long asOfSeq = body.getLong();
		Instant issuedAt = Instant.ofEpochSecond(body.getLong());
		int section = body.get();
		UUID lastId = new UUID(body.getLong(), body.getLong());
		if (section < 0 || section >= SECTIONS.length || asOfSeq < 0) {
			throw refused("contenu invalide");
		}
		if (!linkId.equals(link.id())) {
			throw refused("autre liaison");
		}
		if (Duration.between(issuedAt, this.clock.instant()).compareTo(VALIDITY) > 0) {
			log.debug("pageToken refusé : instantané expiré");
			throw new SnapshotExpiredException();
		}
		return new PageToken(link, asOfSeq, issuedAt, SECTIONS[section], lastId);
	}

	/** Contrôle de format : base64url canonique, numéro de format, longueur du kid, longueur totale. */
	private static byte[] readable(String token) {
		if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
			throw refused("format");
		}
		byte[] bytes;
		try {
			bytes = DECODER.decode(token);
		}
		catch (IllegalArgumentException ex) {
			throw refused("format");
		}
		// Encodage canonique seulement : aucun bit inutilisé du dernier caractère ne peut varier.
		if (!ENCODER.encodeToString(bytes).equals(token) || bytes.length < 2 || bytes[0] != FORMAT) {
			throw refused("format");
		}
		int kidLength = bytes[1] & 0xFF;
		if (kidLength < 1 || kidLength > MAX_KID_LENGTH || bytes.length != 2 + kidLength + BODY_BYTES + MAC_BYTES) {
			throw refused("format");
		}
		return bytes;
	}

	private static InvalidPageTokenException refused(String reason) {
		log.debug("pageToken refusé : {}", reason);
		return new InvalidPageTokenException();
	}

	private static byte[] mac(byte[] secret, byte[] data, int length) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			mac.update(data, 0, length);
			return mac.doFinal();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 indisponible", ex);
		}
	}

	private static void putUuid(ByteBuffer buffer, UUID id) {
		buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
	}

}
