package com.geststock.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Versions des migrations présentes sur le classpath ("001", "002"...), triées. */
final class MigrationFiles {

	private MigrationFiles() {
	}

	static List<String> versions() {
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
			return Arrays.stream(resources)
				.map(Resource::getFilename)
				.filter(Objects::nonNull)
				.map(name -> name.substring(1, name.indexOf("__")))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
