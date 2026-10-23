package com.aimastery.gateway.structured;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que SchemaValidator sait reellement verifier.
 *
 * Un mot-cle hors de cette liste serait ignore sans rien dire : le Gateway repondrait
 * "conforme" sur une contrainte jamais verifiee (pattern, format, minItems, oneOf...).
 * On refuse donc le schema avant l'appel au modele — un 400 ne coute aucun token,
 * une fausse garantie coute bien plus cher.
 */
public final class SchemaSupport {

    private static final Set<String> VALIDATED = Set.of(
            "type", "enum", "minimum", "maximum", "minLength", "maxLength",
            "required", "properties", "additionalProperties", "items");

    /** Purement descriptifs : n'imposent rien, ignores sans risque. */
    private static final Set<String> DESCRIPTIVE = Set.of("$schema", "title", "description", "examples");

    private static final List<String> TYPES =
            List.of("object", "array", "string", "number", "integer", "boolean", "null");

    private SchemaSupport() {
    }

    /** Leve InvalidSchemaException si le schema demande une verification non geree. */
    public static void requireSupported(JsonNode schema) {
        List<String> problems = problems(schema);
        if (!problems.isEmpty()) {
            throw new InvalidSchemaException(problems);
        }
    }

    /** Chaque probleme nomme le mot-cle et son chemin dans le schema ($ = racine). */
    public static List<String> problems(JsonNode schema) {
        List<String> problems = new ArrayList<>();
        walk(schema, "$", problems);
        return problems;
    }

    private static void walk(JsonNode schema, String path, List<String> out) {
        if (schema == null || !schema.isObject()) {
            out.add(path + " : un schema doit etre un objet JSON");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String keyword = field.getKey();
            JsonNode value = field.getValue();

            if (DESCRIPTIVE.contains(keyword)) {
                continue;
            }
            if (!VALIDATED.contains(keyword)) {
                out.add(path + " : mot-cle '" + keyword + "' non gere");
                continue;
            }
            checkForm(keyword, value, path, out);
        }
    }

    /**
     * Un mot-cle gere peut quand meme etre ignore s'il a une forme que le validateur ne
     * lit pas ("type": ["string", "null"], additionalProperties en schema...).
     */
    private static void checkForm(String keyword, JsonNode value, String path, List<String> out) {
        switch (keyword) {
            case "type" -> {
                if (!value.isTextual() || !TYPES.contains(value.asText())) {
                    out.add(path + " : type " + value + " non gere (une seule valeur parmi " + TYPES + ")");
                }
            }
            case "properties" -> {
                if (!value.isObject()) {
                    out.add(path + " : 'properties' doit etre un objet");
                    return;
                }
                value.fields().forEachRemaining(property ->
                        walk(property.getValue(), path + ".properties." + property.getKey(), out));
            }
            case "items" -> walk(value, path + ".items", out);
            case "additionalProperties" -> {
                if (!value.isBoolean()) {
                    out.add(path + " : 'additionalProperties' sous forme de schema non gere (booleen uniquement)");
                }
            }
            case "enum", "required" -> {
                if (!value.isArray()) {
                    out.add(path + " : '" + keyword + "' doit etre un tableau");
                }
            }
            case "minimum", "maximum" -> {
                if (!value.isNumber()) {
                    out.add(path + " : '" + keyword + "' doit etre un nombre");
                }
            }
            case "minLength", "maxLength" -> {
                if (!value.canConvertToInt() || !value.isIntegralNumber()) {
                    out.add(path + " : '" + keyword + "' doit etre un entier");
                }
            }
            default -> { }
        }
    }
}
