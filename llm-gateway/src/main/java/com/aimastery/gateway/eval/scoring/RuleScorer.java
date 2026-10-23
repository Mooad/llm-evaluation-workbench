package com.aimastery.gateway.eval.scoring;

import com.aimastery.gateway.eval.Dimension;
import com.aimastery.gateway.eval.DimensionScore;
import com.aimastery.gateway.eval.EvalCase;
import com.aimastery.gateway.structured.JsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Verifications deterministes : gratuites, instantanees, reproductibles.
 *
 * Tout ce qui peut se verifier par une regle l'est ici. Le juge LLM n'intervient
 * que pour ce qu'une regle ne sait pas trancher : le sens, la langue, l'invention.
 */
@Component
public class RuleScorer {

    private static final String METHOD = "regle";
    private static final int SHORT_ANSWER_MAX_WORDS = 3;

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    private static final Pattern THOUSANDS_SEPARATOR =
            Pattern.compile("(?<=\\d)[ ,\\u00A0\\u202F](?=\\d{3}(?!\\d))");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------ correctness

    /** Vide = aucune regle ne sait trancher, il faut le juge. */
    public Optional<DimensionScore> correctness(EvalCase c, String answer) {
        if (c.hasExpectedObject()) {
            return Optional.of(jsonFieldsMatch(c.expectedAnswer(), answer));
        }
        String expected = c.expectedText();
        if (expected == null) {
            return Optional.empty();
        }
        if (isNumber(expected)) {
            return Optional.of(numberMatch(expected, answer));
        }
        if (!"hallucination".equals(c.category()) && wordCount(expected) <= SHORT_ANSWER_MAX_WORDS) {
            return Optional.of(containsMatch(expected, answer));
        }
        return Optional.empty();
    }

    private DimensionScore numberMatch(String expected, String answer) {
        BigDecimal target = toNumber(expected);
        List<String> found = numbersIn(answer);
        boolean hit = found.stream().anyMatch(n -> toNumber(n).compareTo(target) == 0);
        return hit
                ? DimensionScore.pass(Dimension.CORRECTNESS, METHOD, "nombre attendu " + expected + " trouve")
                : DimensionScore.fail(Dimension.CORRECTNESS, METHOD,
                        "attendu " + expected + ", nombres trouves : " + (found.isEmpty() ? "aucun" : found));
    }

    private DimensionScore containsMatch(String expected, String answer) {
        return normalize(answer).contains(normalize(expected))
                ? DimensionScore.pass(Dimension.CORRECTNESS, METHOD, "'" + expected + "' present dans la reponse")
                : DimensionScore.fail(Dimension.CORRECTNESS, METHOD, "'" + expected + "' absent de la reponse");
    }

    private DimensionScore jsonFieldsMatch(JsonNode expected, String answer) {
        JsonNode actual = parseObject(answer);
        if (actual == null) {
            return DimensionScore.fail(Dimension.CORRECTNESS, METHOD, "aucun objet JSON exploitable dans la reponse");
        }
        List<String> wrong = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = actual.get(field.getKey());
            if (value == null || !sameValue(field.getValue(), value)) {
                wrong.add(field.getKey() + " (attendu " + field.getValue() + ", recu "
                        + (value == null ? "absent" : value) + ")");
            }
        }
        return wrong.isEmpty()
                ? DimensionScore.pass(Dimension.CORRECTNESS, METHOD, "toutes les valeurs attendues sont correctes")
                : DimensionScore.fail(Dimension.CORRECTNESS, METHOD, "valeurs incorrectes : " + String.join(", ", wrong));
    }

    // ------------------------------------------------------------------ format

    public FormatOutcome format(EvalCase c, String answer) {
        List<String> checks = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        if (c.hasExpectedObject()) {
            checks.add("objet JSON avec exactement les champs attendus");
            checkJsonKeys(c.expectedAnswer(), answer, violations);
        }

        String expected = c.expectedText();
        if (expected != null && isNumber(expected)) {
            checks.add("uniquement un nombre");
            String cleaned = stripWrapping(answer);
            String normalized = THOUSANDS_SEPARATOR.matcher(cleaned).replaceAll("");
            if (!NUMBER.matcher(normalized).matches()) {
                violations.add("la reponse doit etre uniquement le nombre, recu : '" + abbreviate(answer) + "'");
            }
        }

        JsonNode maxSentences = c.constraint("max_sentences");
        if (maxSentences != null && maxSentences.canConvertToInt()) {
            checks.add("au plus " + maxSentences.asInt() + " phrase(s)");
            int count = sentenceCount(answer);
            if (count > maxSentences.asInt()) {
                violations.add(count + " phrases, maximum " + maxSentences.asInt());
            }
        }

        JsonNode itemCount = c.constraint("item_count");
        if (itemCount != null && itemCount.canConvertToInt()) {
            checks.add(itemCount.asInt() + " elements separes par des virgules");
            checkCommaList(c, answer, itemCount.asInt(), violations);
        }

        JsonNode language = c.constraint("language");
        return new FormatOutcome(checks, violations, language == null ? null : language.asText());
    }

    private void checkJsonKeys(JsonNode expected, String answer, List<String> violations) {
        JsonNode actual = parseObject(answer);
        if (actual == null) {
            violations.add("pas d'objet JSON valide");
            return;
        }
        Set<String> expectedKeys = new HashSet<>();
        expected.fieldNames().forEachRemaining(expectedKeys::add);
        Set<String> actualKeys = new HashSet<>();
        actual.fieldNames().forEachRemaining(actualKeys::add);

        Set<String> missing = new HashSet<>(expectedKeys);
        missing.removeAll(actualKeys);
        Set<String> extra = new HashSet<>(actualKeys);
        extra.removeAll(expectedKeys);

        if (!missing.isEmpty()) {
            violations.add("champs manquants : " + missing);
        }
        if (!extra.isEmpty()) {
            violations.add("champs en trop : " + extra);
        }
    }

    private void checkCommaList(EvalCase c, String answer, int expectedCount, List<String> violations) {
        String body = stripWrapping(answer);
        if (body.contains("\n")) {
            violations.add("reponse sur plusieurs lignes");
        }
        List<String> items = Arrays.stream(body.split(","))
                .map(String::trim)
                .map(item -> item.replaceAll("[.]+$", ""))
                .filter(item -> !item.isEmpty())
                .toList();
        if (items.size() != expectedCount) {
            violations.add(items.size() + " elements, attendu " + expectedCount);
        }
        JsonNode noExplanation = c.constraint("no_explanation");
        if (noExplanation != null && noExplanation.asBoolean()
                && items.stream().anyMatch(item -> wordCount(item) > SHORT_ANSWER_MAX_WORDS)) {
            violations.add("texte explicatif detecte");
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private JsonNode parseObject(String answer) {
        String json = JsonExtractor.extract(answer);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sameValue(JsonNode expected, JsonNode actual) {
        if (expected.isNumber()) {
            return isNumber(actual.asText()) && toNumber(actual.asText()).compareTo(expected.decimalValue()) == 0;
        }
        if (expected.isValueNode() && actual.isValueNode()) {
            return normalize(expected.asText()).equals(normalize(actual.asText()));
        }
        return expected.equals(actual);
    }

    private List<String> numbersIn(String text) {
        String normalized = THOUSANDS_SEPARATOR.matcher(text == null ? "" : text).replaceAll("");
        List<String> found = new ArrayList<>();
        Matcher m = NUMBER.matcher(normalized);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    static int sentenceCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(SENTENCE_BREAK.split(text.trim()))
                .filter(s -> !s.isBlank())
                .count();
    }

    private static boolean isNumber(String text) {
        return text != null && NUMBER.matcher(text.trim()).matches();
    }

    private static BigDecimal toNumber(String text) {
        return new BigDecimal(text.trim().replace(',', '.'));
    }

    private static int wordCount(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /** Retire les enrobages sans valeur : espaces, gras markdown, backticks, point final. */
    private static String stripWrapping(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceAll("^[`*\"']+|[`*\"']+$", "")
                .replaceAll("[.]+$", "")
                .trim();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase().replaceAll("\\s+", " ").replaceAll("[.!?]+$", "").trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }
}
