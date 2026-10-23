package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class EvalDatasetLoaderTest {

    private final EvalDatasetLoader loader = new EvalDatasetLoader(new DefaultResourceLoader(), new ObjectMapper());

    @Test
    void charge_le_golden_dataset_du_projet() {
        List<EvalCase> cases = loader.load("classpath:evaluation/dataset.json");

        assertThat(cases).hasSize(35);
        assertThat(cases).extracting(EvalCase::id).doesNotHaveDuplicates();
    }

    @Test
    void lit_les_differentes_formes_de_reference() {
        List<EvalCase> cases = loader.load("classpath:evaluation/dataset.json");

        EvalCase json = byId(cases, "format_001");
        assertThat(json.hasExpectedObject()).isTrue();
        assertThat(json.expectedAnswer().get("age").asInt()).isEqualTo(29);

        EvalCase math = byId(cases, "math_001");
        assertThat(math.expectedText()).isEqualTo("1800");
        assertThat(math.scores(Dimension.HALLUCINATION)).isFalse();

        EvalCase fr = byId(cases, "fr_001");
        assertThat(fr.expectedMeaning()).isNotBlank();
        assertThat(fr.constraint("language").asText()).isEqualTo("fr");
        assertThat(fr.constraint("max_sentences").asInt()).isEqualTo(1);

        EvalCase list = byId(cases, "instruction_001");
        assertThat(list.expectedValues()).containsExactly("int", "boolean", "double");
    }

    @Test
    void refuse_un_dataset_dont_un_schema_n_est_pas_verifiable(@TempDir Path dir) throws Exception {
        Path dataset = dir.resolve("dataset.json");
        Files.writeString(dataset, """
                [{"id": "x", "prompt": "p", "schema": {"type": "string", "pattern": "^a"}}]""");

        // Refuse au chargement : aucun token depense avant de decouvrir le probleme.
        assertThatThrownBy(() -> loader.load("file:" + dataset))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    void refuse_un_dataset_introuvable() {
        assertThatThrownBy(() -> loader.load("classpath:evaluation/inexistant.json"))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("introuvable");
    }

    private static EvalCase byId(List<EvalCase> cases, String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
