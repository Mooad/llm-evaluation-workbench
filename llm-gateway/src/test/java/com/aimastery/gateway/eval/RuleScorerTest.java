package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.eval.scoring.FormatOutcome;
import com.aimastery.gateway.eval.scoring.RuleScorer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class RuleScorerTest {

    private static final List<EvalCase> CASES = new EvalDatasetLoader(new DefaultResourceLoader(), new ObjectMapper())
            .load("classpath:evaluation/dataset.json");

    private final RuleScorer rules = new RuleScorer();

    private static EvalCase c(String id) {
        return CASES.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // --- nombres ---

    @Test
    void nombre_juste_et_nu() {
        assertThat(rules.correctness(c("math_001"), "1800")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.PASS);
        assertThat(rules.format(c("math_001"), "1800").violations()).isEmpty();
    }

    @Test
    void nombre_juste_mais_bavard_est_correct_mais_mal_formate() {
        String answer = "The service processes 1,800 requests.";

        assertThat(rules.correctness(c("math_001"), answer)).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.PASS);
        assertThat(rules.format(c("math_001"), answer).violations()).isNotEmpty();
    }

    @Test
    void nombre_faux() {
        assertThat(rules.correctness(c("reasoning_002"), "940")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
    }

    // --- texte court ---

    @Test
    void reponse_courte_contenue_dans_une_phrase() {
        assertThat(rules.correctness(c("fact_001"), "The capital of Morocco is Rabat.")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.PASS);
        assertThat(rules.correctness(c("fact_001"), "It is Casablanca.")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
    }

    // --- JSON ---

    @Test
    void json_exact() {
        String answer = "{\"name\": \"Sarah\", \"age\": 29, \"city\": \"Paris\"}";

        assertThat(rules.correctness(c("format_001"), answer)).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.PASS);
        assertThat(rules.format(c("format_001"), answer).violations()).isEmpty();
    }

    @Test
    void json_avec_un_champ_invente_est_juste_mais_mal_formate() {
        String answer = "{\"name\": \"Ahmed\", \"company\": \"NovaTech\", \"role\": \"backend engineer\"}";

        assertThat(rules.correctness(c("extract_001"), answer)).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.PASS);
        assertThat(rules.format(c("extract_001"), answer).violations())
                .anyMatch(v -> v.contains("champs en trop"));
    }

    @Test
    void json_avec_une_valeur_fausse() {
        String answer = "{\"name\": \"Sarah\", \"age\": \"30\", \"city\": \"Paris\"}";

        assertThat(rules.correctness(c("format_001"), answer)).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
    }

    // --- contraintes ---

    @Test
    void liste_de_trois_elements() {
        assertThat(rules.format(c("instruction_001"), "int, long, char").violations()).isEmpty();
        assertThat(rules.format(c("instruction_001"), "int, long").violations())
                .anyMatch(v -> v.contains("2 elements"));
        assertThat(rules.format(c("instruction_001"), "int, long, char, byte").violations()).isNotEmpty();
    }

    @Test
    void une_seule_phrase_et_langue_confiee_au_juge() {
        FormatOutcome ok = rules.format(c("fr_001"), "Une API REST expose des ressources via HTTP.");
        FormatOutcome tooLong = rules.format(c("fr_001"), "Une API REST expose des ressources. Elle utilise HTTP.");

        assertThat(ok.violations()).isEmpty();
        assertThat(ok.requiredLanguage()).isEqualTo("fr");
        assertThat(tooLong.violations()).anyMatch(v -> v.contains("2 phrases"));
    }

    // --- ce que les regles ne savent pas trancher ---

    @Test
    void le_sens_et_l_abstention_sont_laisses_au_juge() {
        assertThat(rules.correctness(c("hallucination_001"), "I don't know.")).isEmpty();
        assertThat(rules.correctness(c("summary_001"), "The gateway routes requests.")).isEmpty();
        assertThat(rules.correctness(c("instruction_001"), "int, long, char")).isEmpty();
    }
}
