package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@code pageToken} d'instantané (lot 1, étape 7 ; contrat P4, v9-D2) : HMAC-SHA256, {@code kid}, numéro de
 * format, base64url, 512 caractères au plus ; contrôles dans l'ordre format → {@code kid} connu → signature →
 * liaison → expiration ; rotation des clés ; aucune clé dans les journaux ni dans les messages.
 */
class PageTokenCodecTest {

	private static final String OLD_SECRET = "page-token-test-key-0-previous-not-a-secret";

	private static final String CURRENT_SECRET = "page-token-test-key-1-current-not-a-secret";

	private static final Instant FIRST_PAGE = Instant.parse("2026-10-29T08:00:00Z");

	private final SettableClock clock = new SettableClock(FIRST_PAGE);

	private final LinkRef link = new LinkRef(UUID.randomUUID());

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

	@Test
	void aTokenIsReadBackAsIssued() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		PageToken token = token(this.link, SnapshotSection.PRODUCTS, UUID.randomUUID());

		assertThat(codec.decode(codec.encode(token), this.link)).isEqualTo(token);
	}

	// --- T-C3-5 ------------------------------------------------------------------------------------------

	@Test
	void aTokenOfAnotherLinkIsRefused() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.CATEGORIES, nil()));

		assertInvalid(() -> codec.decode(encoded, new LinkRef(UUID.randomUUID())));
	}

	@Test
	void aTokenAlteredByOneCharacterIsRefused() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.AVAILABILITY, UUID.randomUUID()));

		for (int position = 0; position < encoded.length(); position++) {
			char replaced = encoded.charAt(position) == 'A' ? 'B' : 'A';
			String altered = encoded.substring(0, position) + replaced + encoded.substring(position + 1);
			int at = position;
			assertThatThrownBy(() -> codec.decode(altered, this.link)).as("caractère %d altéré", at)
				.isInstanceOfSatisfying(InvalidPageTokenException.class, e -> assertCode(e, ErrorCode.INVALID_PAGE_TOKEN, 400));
		}
	}

	@Test
	void unreadableTokensAreRefused() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		for (String garbage : List.of("", "!!!", "abc", "a".repeat(600),
				Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 9, 1, 2, 3 }))) {
			assertInvalid(() -> codec.decode(garbage, this.link));
		}
		assertInvalid(() -> codec.decode(null, this.link));
	}

	@Test
	void theCurrencyTravelsInTheTokenAndCannotBeAltered() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.PRODUCTS, UUID.randomUUID()));
		assertThat(codec.decode(encoded, this.link).currency()).isEqualTo("XOF");

		// Devise réécrite dans le jeton (XOF → EUR), base64url canonique : refusée par la signature.
		byte[] bytes = Base64.getUrlDecoder().decode(encoded);
		int currencyAt = 2 + bytes[1] + 16 + 8 + 8;
		assertThat(new String(bytes, currencyAt, 3, StandardCharsets.US_ASCII)).isEqualTo("XOF");
		System.arraycopy("EUR".getBytes(StandardCharsets.US_ASCII), 0, bytes, currencyAt, 3);
		String altered = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		assertInvalid(() -> codec.decode(altered, this.link));
	}

	// --- T-C3-6 ------------------------------------------------------------------------------------------

	@Test
	void aTokenOlderThanTwentyFourHoursIsExpired() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.PRODUCTS, UUID.randomUUID()));

		this.clock.set(FIRST_PAGE.plus(Duration.ofHours(24)).minusSeconds(1));
		assertThat(codec.decode(encoded, this.link).asOfSeq()).isEqualTo(42L);
		this.clock.set(FIRST_PAGE.plus(Duration.ofHours(24)));
		assertThat(codec.decode(encoded, this.link).asOfSeq()).as("24 h exactement : accepté").isEqualTo(42L);

		this.clock.set(FIRST_PAGE.plus(Duration.ofHours(24)).plusSeconds(1));
		assertThatThrownBy(() -> codec.decode(encoded, this.link))
			.isInstanceOfSatisfying(SnapshotExpiredException.class, e -> assertCode(e, ErrorCode.SNAPSHOT_EXPIRED, 410));
	}

	@Test
	void everyOtherFailureIsCheckedBeforeTheExpiry() {
		PageTokenCodec codec = codec("k1", Map.of("k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.PRODUCTS, UUID.randomUUID()));
		this.clock.set(FIRST_PAGE.plus(Duration.ofDays(3)));

		assertInvalid(() -> codec.decode(encoded, new LinkRef(UUID.randomUUID())));
		assertInvalid(() -> codec.decode(encoded.substring(0, encoded.length() - 2) + "AA", this.link));
		assertInvalid(() -> codec("k2", Map.of("k2", CURRENT_SECRET)).decode(encoded, this.link));
	}

	// --- T-C3-11 -----------------------------------------------------------------------------------------

	@Test
	void aTokenSignedByThePreviousKeyStillListedIsAccepted() {
		PageTokenCodec before = codec("k0", Map.of("k0", OLD_SECRET));
		String encoded = before.encode(token(this.link, SnapshotSection.CATEGORIES, nil()));

		PageTokenCodec rotated = codec("k1", Map.of("k0", OLD_SECRET, "k1", CURRENT_SECRET));
		assertThat(rotated.decode(encoded, this.link).section()).isEqualTo(SnapshotSection.CATEGORIES);
		assertThat(rotated.encode(token(this.link, SnapshotSection.CATEGORIES, nil())))
			.as("la clé courante signe").isNotEqualTo(encoded);
	}

	@Test
	void aTokenSignedByAnUnknownKidIsRefused() {
		String encoded = codec("k0", Map.of("k0", OLD_SECRET)).encode(token(this.link, SnapshotSection.CATEGORIES, nil()));

		assertInvalid(() -> codec("k1", Map.of("k1", CURRENT_SECRET)).decode(encoded, this.link));
		// Même kid, autre clé : signature invalide.
		assertInvalid(() -> codec("k0", Map.of("k0", CURRENT_SECRET)).decode(encoded, this.link));
	}

	// --- Longueur, configuration, journaux ----------------------------------------------------------------

	@Test
	void theLongestTokenIsAtMost512Characters() {
		String longestKid = "k".repeat(PageTokenCodec.MAX_KID_LENGTH);
		PageTokenCodec codec = codec(longestKid, Map.of(longestKid, CURRENT_SECRET));
		PageToken longest = new PageToken(this.link, Long.MAX_VALUE, FIRST_PAGE, "XOF", SnapshotSection.AVAILABILITY,
				new UUID(-1L, -1L));

		String encoded = codec.encode(longest);
		System.out.println("Longueur maximale mesurée d'un pageToken : " + encoded.length() + " caractères");
		assertThat(encoded.length()).isLessThanOrEqualTo(512);
		assertThat(encoded).matches("[A-Za-z0-9_-]+");
		assertThat(codec.decode(encoded, this.link)).isEqualTo(longest);
	}

	@Test
	void aMalformedOrShortKeyIsRefusedWithoutRevealingIt() {
		assertThatThrownBy(() -> new PageTokenCodec(properties("k1", Map.of("k1", base64("trop-courte"))), this.clock))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 octets")
			.hasMessageNotContaining(base64("trop-courte"));
		assertThatThrownBy(() -> new PageTokenCodec(properties("k1", Map.of("k1", "pas du base64 !")), this.clock))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64")
			.hasMessageNotContaining("pas du base64");
		PageTokenCodec withoutCurrentKey = new PageTokenCodec(properties(null, Map.of()), this.clock);
		assertThatThrownBy(() -> withoutCurrentKey.encode(token(this.link, SnapshotSection.CATEGORIES, nil())))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("current-kid");
	}

	@Test
	void noKeyEverAppearsInTheLogsOrTheErrors() {
		PageTokenCodec codec = codec("k1", Map.of("k0", OLD_SECRET, "k1", CURRENT_SECRET));
		String encoded = codec.encode(token(this.link, SnapshotSection.PRODUCTS, UUID.randomUUID()));
		List<Throwable> failures = new java.util.ArrayList<>();
		for (Runnable failing : List.<Runnable>of(() -> codec.decode(encoded, new LinkRef(UUID.randomUUID())),
				() -> codec.decode("x" + encoded, this.link), () -> codec.decode("!!!", this.link),
				() -> codec("k9", Map.of("k9", CURRENT_SECRET)).decode(encoded, this.link))) {
			try {
				failing.run();
			}
			catch (RuntimeException ex) {
				failures.add(ex);
			}
		}
		this.clock.set(FIRST_PAGE.plus(Duration.ofDays(2)));
		try {
			codec.decode(encoded, this.link);
		}
		catch (RuntimeException ex) {
			failures.add(ex);
		}

		assertThat(failures).hasSize(5);
		List<String> secrets = List.of(OLD_SECRET, CURRENT_SECRET, base64(OLD_SECRET), base64(CURRENT_SECRET));
		for (ILoggingEvent event : this.logs.list) {
			for (String secret : secrets) {
				assertThat(event.getFormattedMessage()).doesNotContain(secret);
			}
		}
		for (Throwable failure : failures) {
			for (String secret : secrets) {
				assertThat(String.valueOf(failure.getMessage())).doesNotContain(secret);
			}
		}
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private PageTokenCodec codec(String currentKid, Map<String, String> rawSecrets) {
		Map<String, String> keys = new java.util.HashMap<>();
		rawSecrets.forEach((kid, secret) -> keys.put(kid, base64(secret)));
		return new PageTokenCodec(properties(currentKid, keys), this.clock);
	}

	private static PageTokenProperties properties(String currentKid, Map<String, String> keys) {
		return new PageTokenProperties(currentKid, keys);
	}

	private PageToken token(LinkRef owner, SnapshotSection section, UUID lastId) {
		return new PageToken(owner, 42L, this.clock.instant(), "XOF", section, lastId);
	}

	private static UUID nil() {
		return new UUID(0L, 0L);
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(InvalidPageTokenException.class, e -> assertCode(e, ErrorCode.INVALID_PAGE_TOKEN, 400));
	}

	private static void assertCode(ApiException exception, ErrorCode code, int status) {
		assertThat(exception.getCode()).isEqualTo(code);
		assertThat(exception.getStatus().value()).isEqualTo(status);
	}

	/** Horloge réglable : heure des pages suivantes. */
	private static final class SettableClock extends Clock {

		private Instant now;

		SettableClock(Instant now) {
			this.now = now;
		}

		void set(Instant instant) {
			this.now = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
