package com.geststock.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fous de la stratégie de persistance, vérifiés sans base de données :
 * aucun profil ne laisse Hibernate créer le schéma, et chaque migration versionnée possède
 * son script d'annulation documenté.
 */
class PersistenceConfigurationTest {

	private static final Pattern VERSIONED = Pattern.compile("V(\\d{3})__([a-z0-9_]+)\\.sql");

	private static final Pattern UNDO = Pattern.compile("U(\\d{3})__([a-z0-9_]+)\\.sql");

	@ParameterizedTest
	@ValueSource(strings = { "application.yml", "application-dev.yml", "application-test.yml", "application-prod.yml" })
	void noProfileLetsHibernateGenerateTheSchema(String file) throws IOException {
		Map<String, Object> properties = load(file);
		Object ddlAuto = properties.get("spring.jpa.hibernate.ddl-auto");
		if (file.equals("application.yml")) {
			assertThat(ddlAuto).as("valeur de référence dans %s", file).hasToString("validate");
		}
		else {
			assertThat(ddlAuto).as("un profil ne doit pas surcharger ddl-auto (%s)", file).isNull();
		}
		assertThat(properties.get("spring.jpa.generate-ddl")).as(file).isNull();
	}

	@Test
	void flywayIsTheSafeSchemaOwner() throws IOException {
		Map<String, Object> properties = load("application.yml");
		assertThat(properties.get("spring.flyway.enabled")).hasToString("true");
		assertThat(properties.get("spring.flyway.clean-disabled")).hasToString("true");
		assertThat(properties.get("spring.flyway.validate-on-migrate")).hasToString("true");
		assertThat(properties.get("spring.jpa.open-in-view")).hasToString("false");
	}

	@Test
	void everyVersionedMigrationHasAMatchingRollbackScript() throws IOException {
		Set<String> versions = fileNames("classpath:db/migration/*.sql", VERSIONED);
		Set<String> rollbacks = fileNames("classpath:db/rollback/*.sql", UNDO);

		assertThat(versions).as("migrations versionnées").isNotEmpty();
		assertThat(rollbacks).as("chaque V{NNN}__x.sql doit avoir son U{NNN}__x.sql").isEqualTo(versions);
	}

	@Test
	void migrationFileNamesFollowTheConvention() throws IOException {
		Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*");
		assertThat(resources).allSatisfy(resource -> assertThat(resource.getFilename())
			.as("nommage V{NNN}__module_sujet.sql")
			.matches(VERSIONED.pattern()));

		Set<Integer> numbers = Arrays.stream(resources)
			.map(resource -> VERSIONED.matcher(resource.getFilename()))
			.filter(Matcher::matches)
			.map(matcher -> Integer.parseInt(matcher.group(1)))
			.collect(Collectors.toCollection(TreeSet::new));
		assertThat(numbers).as("numérotation continue à partir de 001")
			.containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, numbers.size()).boxed().toList());
	}

	@Test
	void devSeedsAreRepeatableMigrations() throws IOException {
		Resource[] seeds = new PathMatchingResourcePatternResolver().getResources("classpath:db/seed/dev/*.sql");
		assertThat(seeds).allSatisfy(seed -> assertThat(seed.getFilename()).startsWith("R__dev_"));
	}

	private static Set<String> fileNames(String pattern, Pattern format) throws IOException {
		Set<String> keys = new TreeSet<>();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
			Matcher matcher = format.matcher(resource.getFilename());
			if (matcher.matches()) {
				keys.add(matcher.group(1) + "__" + matcher.group(2));
			}
		}
		return keys;
	}

	private static Map<String, Object> load(String file) throws IOException {
		ClassPathResource resource = new ClassPathResource(file);
		Map<String, Object> flattened = new java.util.HashMap<>();
		try (InputStream ignored = resource.getInputStream()) {
			for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, resource)) {
				if (source.getSource() instanceof Map<?, ?> map) {
					map.forEach((key, value) -> flattened.put(String.valueOf(key), value));
				}
			}
		}
		return flattened;
	}

}
