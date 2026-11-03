package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Workflow du cron de la maintenance (lot 3, G20e ; plan § 33.8, § 37), vérifié hors ligne : planification toutes les
 * 15 minutes (G20-D6), un seul passage à la fois, aucune permission au-delà de la lecture du dépôt, rien sans la
 * variable d'activation, délai borné ; secret transmis à {@code curl} par l'entrée standard, jamais en argument ni
 * affiché. Le comportement du script est vérifié par {@code scripts/integration/maintenance-pass.test.sh}.
 */
class MaintenanceWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/integration-maintenance.yml");

	private static final Path SCRIPT = Path.of("scripts/integration/maintenance-pass.sh");

	@Test
	@SuppressWarnings("unchecked")
	void theWorkflowRunsEveryFifteenMinutesOnePassAtATimeWithReadOnlyPermissions() throws IOException {
		Map<Object, Object> workflow = new Yaml().load(read(WORKFLOW));
		// YAML 1.1 (SnakeYAML) : la clé « on » est lue comme le booléen vrai.
		Map<String, Object> triggers = (Map<String, Object>) workflow.getOrDefault("on", workflow.get(Boolean.TRUE));
		List<Map<String, String>> schedule = (List<Map<String, String>>) triggers.get("schedule");
		assertThat(schedule).singleElement().satisfies(entry -> assertThat(entry.get("cron")).isEqualTo("7,22,37,52 * * * *"));
		assertThat(triggers).containsKey("workflow_dispatch");

		assertThat((Map<String, Object>) workflow.get("permissions")).containsExactly(Map.entry("contents", "read"));
		Map<String, Object> concurrency = (Map<String, Object>) workflow.get("concurrency");
		assertThat(concurrency).containsEntry("group", "integration-maintenance").containsEntry("cancel-in-progress", false);

		Map<String, Object> job = (Map<String, Object>) ((Map<String, Object>) workflow.get("jobs")).get("maintenance");
		assertThat(job.get("if")).as("rien sans la variable d'activation").isEqualTo("vars.GESTSTOCK_MAINTENANCE_ENABLED == 'true'");
		assertThat((Integer) job.get("timeout-minutes")).as("3 tentatives de 120 s au plus et leurs attentes").isBetween(8, 15);
		List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
		assertThat(steps).anySatisfy(step -> assertThat(String.valueOf(step.get("uses"))).startsWith("actions/checkout@"));
		Map<String, Object> pass = steps.stream().filter(step -> step.containsKey("run")).findFirst().orElseThrow();
		assertThat(pass.get("run")).isEqualTo("bash scripts/integration/maintenance-pass.sh");
		assertThat((Map<String, String>) pass.get("env"))
			.containsEntry("GESTSTOCK_MAINTENANCE_URL", "${{ vars.GESTSTOCK_MAINTENANCE_URL }}")
			.containsEntry("GESTSTOCK_MAINTENANCE_TOKEN", "${{ secrets.GESTSTOCK_MAINTENANCE_TOKEN }}");
	}

	@Test
	void theSecretIsNeverAnArgumentNorPrinted() throws IOException {
		String workflow = read(WORKFLOW);
		assertThat(workflow.split("secrets\\.", -1)).as("le secret n'apparaît qu'une fois, dans l'environnement du pas")
			.hasSize(2);
		String script = read(SCRIPT);
		assertThat(script).contains("set -euo pipefail").doesNotContain("set -x");
		assertThat(script).as("en-tête transmis par l'entrée standard").contains("curl --config -");
		assertThat(script).contains("--max-time");
		assertThat(script.lines().filter(line -> !line.strip().startsWith("#"))
			.filter(line -> line.contains("$GESTSTOCK_MAINTENANCE_TOKEN") || line.contains("${GESTSTOCK_MAINTENANCE_TOKEN"))
			.filter(line -> line.contains("echo") || line.contains("curl ")))
			.as("jamais affiché ni passé en argument").isEmpty();
	}

	private static String read(Path path) throws IOException {
		assertThat(path).as("fichier attendu : %s", path).exists();
		return Files.readString(path, StandardCharsets.UTF_8);
	}

}
