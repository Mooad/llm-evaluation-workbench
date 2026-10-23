package com.aimastery.gateway.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Validation d'une sortie de modele contre un JSON Schema.
 *
 * Sous-ensemble volontairement limite : type, required, properties, enum, items,
 * minimum/maximum, minLength/maxLength, additionalProperties. C'est ce qu'on ecrit
 * reellement dans un schema de sortie LLM. Tout le reste est refuse en amont par
 * SchemaSupport : ce qui arrive ici est entierement verifiable.
 *
 * Pourquoi pas une librairie : networknt 3.x est passe a Jackson 3, incompatible
 * avec le Jackson 2 de Spring Boot 3.2. Un validateur cible coute moins cher qu'un
 * conflit de versions transverse.
 */
@Component
public class SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredOutcome validate(String modelOutput, JsonNode schema) {
        String candidate = JsonExtractor.extract(modelOutput);
        if (candidate == null) {
            return StructuredOutcome.invalid(modelOutput,
                    List.of("Aucun JSON trouve dans la reponse du modele."));
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(candidate);
        } catch (Exception e) {
            return StructuredOutcome.invalid(modelOutput,
                    List.of("JSON malforme : " + e.getMessage()));
        }

        List<String> violations = new ArrayList<>();
        check(parsed, schema, "$", violations);

        return violations.isEmpty()
                ? StructuredOutcome.ok(parsed, modelOutput)
                : StructuredOutcome.invalid(modelOutput, List.copyOf(violations));
    }

    private void check(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (schema == null || !schema.isObject()) {
            return;
        }

        checkType(node, schema, path, out);
        checkEnum(node, schema, path, out);
        checkNumberBounds(node, schema, path, out);
        checkStringLength(node, schema, path, out);

        if (node.isObject()) {
            checkRequired(node, schema, path, out);
            checkProperties(node, schema, path, out);
            checkAdditionalProperties(node, schema, path, out);
        }

        if (node.isArray() && schema.has("items")) {
            for (int i = 0; i < node.size(); i++) {
                check(node.get(i), schema.get("items"), path + "[" + i + "]", out);
            }
        }
    }

    private void checkType(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!schema.has("type")) {
            return;
        }
        String expected = schema.get("type").asText();
        if (!matchesType(node, expected)) {
            out.add(path + " : type attendu '" + expected + "', recu '" + actualType(node) + "'");
        }
    }

    private void checkEnum(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!schema.has("enum") || !schema.get("enum").isArray()) {
            return;
        }
        for (JsonNode allowed : schema.get("enum")) {
            if (allowed.equals(node)) {
                return;
            }
        }
        out.add(path + " : valeur '" + node.asText() + "' hors de l'enumeration " + schema.get("enum"));
    }

    private void checkNumberBounds(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!node.isNumber()) {
            return;
        }
        if (schema.has("minimum") && node.asDouble() < schema.get("minimum").asDouble()) {
            out.add(path + " : " + node.asDouble() + " < minimum " + schema.get("minimum").asDouble());
        }
        if (schema.has("maximum") && node.asDouble() > schema.get("maximum").asDouble()) {
            out.add(path + " : " + node.asDouble() + " > maximum " + schema.get("maximum").asDouble());
        }
    }

    private void checkStringLength(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!node.isTextual()) {
            return;
        }
        int length = node.asText().length();
        if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
            out.add(path + " : longueur " + length + " < minLength " + schema.get("minLength").asInt());
        }
        if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
            out.add(path + " : longueur " + length + " > maxLength " + schema.get("maxLength").asInt());
        }
    }

    private void checkRequired(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!schema.has("required") || !schema.get("required").isArray()) {
            return;
        }
        for (JsonNode required : schema.get("required")) {
            if (!node.has(required.asText())) {
                out.add(path + " : champ obligatoire manquant '" + required.asText() + "'");
            }
        }
    }

    private void checkProperties(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!schema.has("properties")) {
            return;
        }
        JsonNode properties = schema.get("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (node.has(field.getKey())) {
                check(node.get(field.getKey()), field.getValue(), path + "." + field.getKey(), out);
            }
        }
    }

    private void checkAdditionalProperties(JsonNode node, JsonNode schema, String path, List<String> out) {
        if (!schema.has("additionalProperties") || schema.get("additionalProperties").asBoolean(true)) {
            return;
        }
        JsonNode properties = schema.path("properties");
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!properties.has(name)) {
                out.add(path + " : champ inattendu '" + name + "' (additionalProperties = false)");
            }
        }
    }

    private boolean matchesType(JsonNode node, String expected) {
        return switch (expected) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    private String actualType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "inconnu";
    }
}
