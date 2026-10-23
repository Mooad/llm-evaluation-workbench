package com.aimastery.gateway.structured;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode schema() throws Exception {
        return mapper.readTree("""
                {
                  "type": "object",
                  "required": ["jobId", "status"],
                  "additionalProperties": false,
                  "properties": {
                    "jobId": {"type": "integer"},
                    "status": {"type": "string", "enum": ["DONE", "FAILED"]},
                    "retries": {"type": "integer", "minimum": 0}
                  }
                }
                """);
    }

    @Test
    void accepte_une_sortie_conforme() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "{\"jobId\": 42, \"status\": \"FAILED\"}", schema());

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.data().get("jobId").asInt()).isEqualTo(42);
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    void extrait_le_json_d_un_bloc_de_code() throws Exception {
        String reponse = """
                Voici le resultat :

                ```json
                {"jobId": 42, "status": "DONE"}
                ```
                """;

        assertThat(validator.validate(reponse, schema()).valid()).isTrue();
    }

    @Test
    void extrait_le_json_noye_dans_du_texte() throws Exception {
        String reponse = "Bien sur ! {\"jobId\": 7, \"status\": \"DONE\"} J'espere que cela aide.";

        StructuredOutcome outcome = validator.validate(reponse, schema());

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.data().get("jobId").asInt()).isEqualTo(7);
    }

    @Test
    void signale_un_champ_obligatoire_manquant() throws Exception {
        StructuredOutcome outcome = validator.validate("{\"jobId\": 42}", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("status"));
    }

    @Test
    void signale_un_type_incorrect() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "{\"jobId\": \"quarante-deux\", \"status\": \"DONE\"}", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("integer"));
    }

    @Test
    void signale_une_valeur_hors_enumeration() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "{\"jobId\": 42, \"status\": \"EN_COURS\"}", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("enumeration"));
    }

    @Test
    void signale_un_champ_inattendu() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "{\"jobId\": 42, \"status\": \"DONE\", \"inventé\": true}", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("inattendu"));
    }

    @Test
    void signale_un_minimum_non_respecte() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "{\"jobId\": 42, \"status\": \"DONE\", \"retries\": -1}", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("minimum"));
    }

    @Test
    void signale_l_absence_totale_de_json() throws Exception {
        StructuredOutcome outcome = validator.validate(
                "Je ne peux pas repondre a cette question.", schema());

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> v.contains("Aucun JSON"));
    }

    @Test
    void conserve_toujours_la_sortie_brute_meme_en_echec() throws Exception {
        String reponse = "pas de json ici";

        assertThat(validator.validate(reponse, schema()).raw()).isEqualTo(reponse);
    }
}
