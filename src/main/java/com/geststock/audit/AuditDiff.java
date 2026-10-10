package com.geststock.audit;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * États avant/après et différences champ par champ, prêts à stocker en JSON. Les champs
 * sensibles (mot de passe, jeton, secret, empreinte) sont masqués ; les champs techniques
 * (version, dates de mise à jour) ne comptent pas comme des modifications.
 */
final class AuditDiff {

	static final String MASK = "***";

	private static final Pattern SENSITIVE = Pattern.compile("(?i).*(password|secret|token|hash).*");

	private static final Set<String> TECHNICAL = Set.of("version", "createdAt", "createdBy", "updatedAt", "updatedBy",
			"createdByUserId", "updatedByUserId");

	private final JsonMapper json;

	AuditDiff(JsonMapper json) {
		this.json = json;
	}

	/** État sérialisé et masqué, ou {@code null}. */
	JsonNode state(Object value) {
		if (value == null) {
			return null;
		}
		return redact(this.json.valueToTree(value));
	}

	/**
	 * Différences entre deux états : {@code [{field, oldValue, newValue}]}, champs de premier
	 * niveau, dans l'ordre de l'état final. {@code null} si l'un des deux états manque ou
	 * n'est pas un objet.
	 */
	ArrayNode changes(JsonNode before, JsonNode after) {
		if (!(before instanceof ObjectNode old) || !(after instanceof ObjectNode updated)) {
			return null;
		}
		Set<String> fields = new LinkedHashSet<>();
		updated.properties().forEach(entry -> fields.add(entry.getKey()));
		old.properties().forEach(entry -> fields.add(entry.getKey()));
		ArrayNode changes = this.json.createArrayNode();
		for (String field : fields) {
			if (TECHNICAL.contains(field)) {
				continue;
			}
			JsonNode oldValue = old.get(field);
			JsonNode newValue = updated.get(field);
			if (same(oldValue, newValue)) {
				continue;
			}
			ObjectNode change = changes.addObject();
			change.put("field", field);
			change.set("oldValue", oldValue != null ? oldValue : this.json.nullNode());
			change.set("newValue", newValue != null ? newValue : this.json.nullNode());
		}
		return changes;
	}

	private static boolean same(JsonNode a, JsonNode b) {
		boolean aEmpty = a == null || a.isNull();
		boolean bEmpty = b == null || b.isNull();
		if (aEmpty || bEmpty) {
			return aEmpty == bEmpty;
		}
		if (a.isNumber() && b.isNumber()) {
			return a.decimalValue().compareTo(b.decimalValue()) == 0;
		}
		return a.equals(b);
	}

	private JsonNode redact(JsonNode node) {
		if (node instanceof ObjectNode object) {
			for (Map.Entry<String, JsonNode> entry : Set.copyOf(object.properties())) {
				if (SENSITIVE.matcher(entry.getKey()).matches() && !entry.getValue().isNull()) {
					object.put(entry.getKey(), MASK);
				}
				else {
					redact(entry.getValue());
				}
			}
		}
		else if (node instanceof ArrayNode array) {
			array.forEach(this::redact);
		}
		return node;
	}

}
