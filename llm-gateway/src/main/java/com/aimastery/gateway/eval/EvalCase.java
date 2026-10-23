package com.aimastery.gateway.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Un cas du golden dataset, tel qu'ecrit dans evaluation/dataset.json.
 *
 * La reference peut prendre plusieurs formes selon le cas : reponse exacte (texte
 * ou objet JSON), sens attendu, critere d'acceptation, exemple de valeurs valides.
 * Un schema optionnel fait passer le cas par la sortie structuree.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(String id,
                       String category,
                       String prompt,
                       @JsonProperty("expected_answer") JsonNode expectedAnswer,
                       @JsonProperty("expected_meaning") String expectedMeaning,
                       @JsonProperty("accepted_meaning") String acceptedMeaning,
                       @JsonProperty("expected_values") List<String> expectedValues,
                       JsonNode constraints,
                       JsonNode schema,
                       Scoring scoring) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scoring(boolean correctness, boolean format, boolean hallucination) {
    }

    /** Sans bloc scoring, toutes les dimensions sont evaluees. */
    public boolean scores(Dimension dimension) {
        if (scoring == null) {
            return true;
        }
        return switch (dimension) {
            case CORRECTNESS -> scoring.correctness();
            case FORMAT -> scoring.format();
            case HALLUCINATION -> scoring.hallucination();
        };
    }

    /** Avec un schema, le cas passe par /chat/structured et FORMAT = verdict du schema. */
    public boolean hasSchema() {
        return present(schema);
    }

    public boolean hasExpectedObject() {
        return present(expectedAnswer) && expectedAnswer.isObject();
    }

    /** La reponse attendue si c'est une valeur simple (texte ou nombre), sinon null. */
    public String expectedText() {
        return present(expectedAnswer) && expectedAnswer.isValueNode() ? expectedAnswer.asText() : null;
    }

    public JsonNode constraint(String name) {
        if (!present(constraints)) {
            return null;
        }
        JsonNode value = constraints.get(name);
        return present(value) ? value : null;
    }

    static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }
}
