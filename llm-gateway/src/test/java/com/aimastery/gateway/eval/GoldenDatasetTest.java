package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.eval.scoring.RuleScorer;
import com.aimastery.gateway.structured.SchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Controle de coherence du golden dataset lui-meme.
 *
 * Un cas mal ecrit (reference contradictoire, aucune dimension notee) ne se voit
 * qu'au moment de l'eval, apres avoir paye les tokens — et il fausse le passRate
 * sans que rien ne le signale. On le detecte ici, sans modele.
 */
class GoldenDatasetTest {

    private static final List<EvalCase> CASES = new EvalDatasetLoader(new DefaultResourceLoader(), new ObjectMapper())
            .load("classpath:evaluation/dataset.json");

    /** Reponse parfaite pour chaque cas notable par regle. */
    private static final Map<String, String> IDEAL = Map.ofEntries(
            Map.entry("fact_001", "Rabat"),
            Map.entry("fact_002", "Nouakchott"),
            Map.entry("fact_003", "404"),
            Map.entry("math_001", "1800"),
            Map.entry("reasoning_002", "950"),
            Map.entry("reasoning_003", "30"),
            Map.entry("reasoning_004", "32.8"),
            Map.entry("reasoning_005", "43.2"),
            Map.entry("format_001", "{\"name\":\"Sarah\",\"age\":29,\"city\":\"Paris\"}"),
            Map.entry("format_002", "{\"jobId\":4412,\"status\":\"FAILED\",\"retries\":3}"),
            Map.entry("format_003", "{\"languages\":[\"French\",\"Arabic\",\"English\"],\"count\":3}"),
            Map.entry("extract_001", "{\"name\":\"Ahmed\",\"company\":\"NovaTech\"}"),
            Map.entry("extract_002", "{\"service\":\"payment-service\",\"code\":\"E4012\"}"),
            Map.entry("extract_003", "{\"codec\":\"H.265\",\"width\":3840,\"height\":2160,\"hdr\":false}"),
            Map.entry("extract_004", "{\"invoice_number\":2291,\"amount\":1450}"),
            Map.entry("summary_001", "The gateway routes each request to an LLM provider and returns a normalized response."),
            Map.entry("summary_002", "A release that doubled the buffer size crashed the transcoding pods until it was rolled back."),
            Map.entry("summary_003", "Le service transcrit, synchronise et traduit les sous-titres. Un operateur peut les corriger."),
            Map.entry("fr_001", "Une API REST permet a des systemes de communiquer via HTTP."),
            Map.entry("fr_002", "La video n'a pas pu etre transcodee car le fichier source est corrompu."),
            Map.entry("ar_001", "ملف SRT هو ملف نصي يحتوي على الترجمات مع توقيت ظهورها."),
            Map.entry("es_001", "Un contenedor Docker es un entorno aislado que empaqueta una aplicacion con sus dependencias."),
            Map.entry("instruction_001", "int, boolean, double"),
            Map.entry("instruction_002", "GET, POST, PUT, DELETE"),
            Map.entry("instruction_003", "MKV, AVI, MOV, WEBM, FLV"),
            Map.entry("schema_001", "{\"component\":\"transcoding\",\"severity\":\"critical\",\"status\":\"OPEN\"}"),
            Map.entry("schema_002", "{\"name\":\"Karim Alaoui\",\"email\":\"k.alaoui@novatech.example\"}"),
            Map.entry("schema_003", "{\"videoId\":7781,\"tracks\":["
                    + "{\"language\":\"fr\",\"format\":\"VTT\",\"cues\":1240},"
                    + "{\"language\":\"ar\",\"format\":\"SRT\",\"cues\":1198},"
                    + "{\"language\":\"en\",\"format\":\"VTT\",\"cues\":1240}]}"),
            Map.entry("schema_004", "{\"successRate\":94,\"failures\":3}"),
            Map.entry("schema_005", "{\"jobId\":88,\"level\":\"WARN\",\"retry\":2,\"maxRetries\":5,\"timeout\":true}"));

    private final RuleScorer rules = new RuleScorer();
    private final SchemaValidator schemaValidator = new SchemaValidator();

    @Test
    void le_dataset_compte_35_cas_sur_8_categories() {
        assertThat(CASES).hasSize(35);
        assertThat(CASES).extracting(EvalCase::category).containsOnly(
                "factual", "reasoning", "structured_output", "extraction",
                "hallucination", "summarization", "multilingual", "instruction_following");
    }

    @Test
    void chaque_cas_note_au_moins_une_dimension_et_porte_une_reference() {
        for (EvalCase c : CASES) {
            assertThat(Arrays.stream(Dimension.values()).anyMatch(c::scores))
                    .as("%s ne note aucune dimension", c.id()).isTrue();
            assertThat(hasReference(c))
                    .as("%s n'a aucune reference pour le juge", c.id()).isTrue();
        }
    }

    @Test
    void une_reponse_ideale_passe_toutes_les_regles() {
        for (EvalCase c : CASES) {
            String ideal = IDEAL.get(c.id());
            if (ideal == null) {
                continue; // cas entierement confie au juge (hallucination)
            }
            Optional<DimensionScore> correctness = rules.correctness(c, ideal);
            correctness.ifPresent(score -> assertThat(score.verdict())
                    .as("%s correctness : %s", c.id(), score.reason()).isEqualTo(Verdict.PASS));
            assertThat(rules.format(c, ideal).violations())
                    .as("%s format", c.id()).isEmpty();
        }
    }

    @Test
    void au_moins_cinq_cas_mesurent_la_sortie_structuree_par_schema() {
        assertThat(CASES).filteredOn(EvalCase::hasSchema).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void la_reponse_ideale_d_un_cas_a_schema_est_conforme_a_ce_schema() {
        for (EvalCase c : CASES) {
            if (c.hasSchema()) {
                assertThat(schemaValidator.validate(IDEAL.get(c.id()), c.schema()).violations())
                        .as("%s ne passe pas son propre schema", c.id()).isEmpty();
            }
        }
    }

    @Test
    void tout_cas_non_confie_au_juge_a_une_reponse_ideale() {
        List<String> missing = CASES.stream()
                .filter(c -> !"hallucination".equals(c.category()))
                .map(EvalCase::id)
                .filter(id -> !IDEAL.containsKey(id))
                .toList();

        assertThat(missing).as("ajouter une reponse ideale dans IDEAL").isEmpty();
    }

    @Test
    void une_reponse_presque_juste_echoue() {
        assertThat(rules.correctness(byId("reasoning_005"), "43")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
        assertThat(rules.correctness(byId("reasoning_004"), "32")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
        assertThat(rules.correctness(byId("format_003"),
                "{\"languages\":[\"English\",\"French\",\"Arabic\"],\"count\":3}")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
        assertThat(rules.correctness(byId("extract_003"),
                "{\"codec\":\"H.265\",\"width\":3840,\"height\":2160,\"hdr\":true}")).get()
                .extracting(DimensionScore::verdict).isEqualTo(Verdict.FAIL);
    }

    private static EvalCase byId(String id) {
        return CASES.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Meme critere que LlmJudge.reference : sinon le juge recoit "(no reference provided)". */
    private static boolean hasReference(EvalCase c) {
        return c.expectedText() != null
                || c.hasExpectedObject()
                || c.expectedMeaning() != null
                || c.acceptedMeaning() != null
                || (c.expectedValues() != null && !c.expectedValues().isEmpty());
    }
}
