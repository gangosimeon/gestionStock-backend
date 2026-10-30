package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.geststock.integration.link.LinkRegistry.SecretMatch;

/**
 * Comparaison du secret présenté (lot 2, étape G14, exigence 3) : les deux empreintes, courante et en attente, sont
 * comparées à chaque vérification, en temps constant ({@link MessageDigest#isEqual}), sur des tableaux de même
 * longueur, quel que soit le secret reconnu (ou aucun) : le travail fait ne révèle pas lequel a servi.
 */
class LinkSecretMatchTest {

	private static final byte[] CURRENT = hash("courant");

	private static final byte[] PENDING = hash("en-attente");

	private static final byte[] OTHER = hash("autre");

	@Test
	void theCurrentSecretIsRecognizedAfterComparingBothHashes() {
		Counting equal = new Counting();
		assertThat(LinkRegistry.match(CURRENT, CURRENT, PENDING, equal)).isEqualTo(SecretMatch.CURRENT);
		equal.assertBothCompared();
	}

	@Test
	void thePendingSecretIsRecognizedAfterComparingBothHashes() {
		Counting equal = new Counting();
		assertThat(LinkRegistry.match(PENDING, CURRENT, PENDING, equal)).isEqualTo(SecretMatch.PENDING);
		equal.assertBothCompared();
	}

	@Test
	void anUnknownSecretIsRefusedAfterComparingBothHashes() {
		Counting equal = new Counting();
		assertThat(LinkRegistry.match(OTHER, CURRENT, PENDING, equal)).isEqualTo(SecretMatch.NONE);
		equal.assertBothCompared();
	}

	@Test
	void withoutAPendingSecretTheComparisonsAreTheSame() {
		Counting equal = new Counting();
		assertThat(LinkRegistry.match(CURRENT, CURRENT, null, equal)).isEqualTo(SecretMatch.CURRENT);
		equal.assertBothCompared();
		Counting none = new Counting();
		assertThat(LinkRegistry.match(OTHER, null, null, none)).as("liaison sans secret").isEqualTo(SecretMatch.NONE);
		none.assertBothCompared();
	}

	private static byte[] hash(String secret) {
		return LinkRegistry.sha256Hex(secret).getBytes(StandardCharsets.US_ASCII);
	}

	/** Comparaison réelle ({@link MessageDigest#isEqual}), dont chaque appel est compté. */
	private static final class Counting implements BiPredicate<byte[], byte[]> {

		private final List<int[]> calls = new ArrayList<>();

		@Override
		public boolean test(byte[] presented, byte[] stored) {
			this.calls.add(new int[] { presented.length, stored.length });
			return MessageDigest.isEqual(presented, stored);
		}

		void assertBothCompared() {
			assertThat(this.calls).as("deux comparaisons, toujours").hasSize(2);
			assertThat(this.calls).as("longueurs égales (64)").allSatisfy(lengths -> {
				assertThat(lengths[0]).isEqualTo(64);
				assertThat(lengths[1]).isEqualTo(64);
			});
		}

	}

}
