package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Un seul point d'écriture du flux d'intégration (T-C3-12) : vérifications structurelles qui cassent
 * le build si quelqu'un ouvre une autre voie d'écriture, dans l'esprit de {@code StockArchitectureTest}.
 */
class IntegrationArchitectureTest {

	private static final Path SOURCES = Path.of("src/main/java");

	private static final Path INTEGRATION_PACKAGE = SOURCES.resolve("com/geststock/integration");

	private static final Pattern FEED_TABLES = Pattern.compile("integration_outbox|integration_feed_state");

	private static final Pattern OUTBOX_INSERT = Pattern.compile("insert\\s+into\\s+integration_outbox", Pattern.CASE_INSENSITIVE);

	@Test
	void noClassOutsideTheIntegrationPackageTouchesTheFeedTablesOrTheWriter() {
		List<Path> offenders = javaSources()
			.filter(path -> !path.startsWith(INTEGRATION_PACKAGE))
			.filter(path -> {
				String source = read(path);
				return FEED_TABLES.matcher(source).find() || source.contains("OutboxWriter");
			})
			.toList();
		assertThat(offenders).as("classes hors com.geststock.integration qui visent le flux d'intégration").isEmpty();
	}

	@Test
	void outboxWriterIsTheOnlyClassThatInsertsIntoTheOutbox() {
		List<String> inserting = javaSources()
			.filter(path -> OUTBOX_INSERT.matcher(read(path)).find())
			.map(path -> path.getFileName().toString())
			.toList();
		assertThat(inserting).containsExactly("OutboxWriter.java");
	}

	@Test
	void outboxWriterIsNotVisibleOutsideItsPackage() {
		int modifiers = OutboxWriter.class.getModifiers();
		assertThat(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)).isFalse();
	}

	private static Stream<Path> javaSources() {
		try {
			return Files.walk(SOURCES).filter(path -> path.toString().endsWith(".java")).toList().stream();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
