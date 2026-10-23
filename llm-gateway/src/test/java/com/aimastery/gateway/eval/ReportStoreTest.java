package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class ReportStoreTest {

    @TempDir
    Path dir;

    private EvalProperties properties() {
        return EvalFixtures.properties(dir.resolve("reports"), dir.resolve("datasets"));
    }

    private ReportStore store() {
        EvalProperties properties = properties();
        DatasetCatalog catalog = new DatasetCatalog(
                new EvalDatasetLoader(new DefaultResourceLoader(), EvalFixtures.MAPPER), properties);
        return new ReportStore(properties, EvalFixtures.MAPPER, catalog);
    }

    @Test
    void liste_un_rapport_avec_son_dataset_et_ses_taux() {
        EvalFixtures.runner(properties(), EvalFixtures.constant("local", "Rabat"))
                .run(EvalFixtures.rulesOnly("local", List.of("fact_001", "math_001")));

        ReportSummary summary = store().list().get(0);

        assertThat(summary.datasetId()).isEqualTo("golden");
        assertThat(summary.datasetHash()).isNotBlank();
        assertThat(summary.filtered()).isTrue();
        assertThat(summary.dimensions().get(Dimension.CORRECTNESS).passed()).isEqualTo(1);
    }

    @Test
    void lit_un_rapport_de_l_ancien_format_sans_empreinte() throws Exception {
        Files.createDirectories(dir.resolve("reports"));
        Files.writeString(dir.resolve("reports/eval-20260920-110419-ollama-c5bca76c.json"), """
                {"runId": "c5bca76c", "startedAt": "2026-09-20T09:04:19Z", "durationMs": 1000,
                 "candidate": "ollama/qwen3-coder:30b", "judge": "desactive",
                 "dataset": "classpath:evaluation/dataset.json", "caseCount": 1,
                 "dimensions": {}, "currency": "USD", "warnings": [],
                 "cases": [{"id": "fact_001", "category": "factual", "scores": []}]}""");

        EvalReport report = store().load("eval-20260920-110419-ollama-c5bca76c");

        assertThat(report.datasetHash()).isNull();
        assertThat(report.cases().get(0).caseHash()).isNull();
        assertThat(store().list()).singleElement()
                .extracting(ReportSummary::datasetId).isEqualTo("golden");
    }

    @Test
    void un_id_qui_sort_du_repertoire_est_refuse() {
        assertThatThrownBy(() -> store().load("../application"))
                .isInstanceOf(ReportNotFoundException.class);
    }
}
