package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class DatasetCatalogTest {

    private static final String VALID = """
            [{"id": "q1", "category": "factual", "prompt": "Capitale du Maroc ?", "expected_answer": "Rabat"}]""";

    @TempDir
    Path dir;

    private DatasetCatalog catalog() {
        return new DatasetCatalog(new EvalDatasetLoader(new DefaultResourceLoader(), EvalFixtures.MAPPER),
                EvalFixtures.properties(dir.resolve("reports"), dir.resolve("datasets")));
    }

    @Test
    void le_golden_dataset_est_toujours_liste_en_premier() {
        DatasetSummary golden = catalog().list().get(0);

        assertThat(golden.id()).isEqualTo(DatasetCatalog.BUILT_IN);
        assertThat(golden.builtIn()).isTrue();
        assertThat(golden.caseCount()).isEqualTo(35);
        assertThat(golden.schemaCases()).isEqualTo(5);
    }

    @Test
    void un_dataset_importe_devient_evaluable() {
        DatasetCatalog catalog = catalog();

        catalog.save("smoke", VALID);

        assertThat(catalog.list()).extracting(DatasetSummary::id).containsExactly("golden", "smoke");
        assertThat(catalog.cases("smoke")).singleElement().extracting(EvalCase::id).isEqualTo("q1");
    }

    @Test
    void un_dataset_invalide_n_est_jamais_ecrit() {
        String duplicated = "[{\"id\": \"q\", \"prompt\": \"a\"}, {\"id\": \"q\", \"prompt\": \"b\"}]";

        assertThatThrownBy(() -> catalog().save("doublons", duplicated))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("duplique");
        assertThat(dir.resolve("datasets/doublons.json")).doesNotExist();
    }

    @Test
    void un_import_n_ecrase_jamais_un_dataset_existant() {
        DatasetCatalog catalog = catalog();
        catalog.save("smoke", VALID);

        assertThatThrownBy(() -> catalog.save("smoke", VALID)).isInstanceOf(DatasetAlreadyExistsException.class);
        assertThatThrownBy(() -> catalog.save("golden", VALID)).isInstanceOf(DatasetAlreadyExistsException.class);
    }

    @Test
    void un_nom_qui_sortirait_du_repertoire_est_refuse() {
        assertThatThrownBy(() -> catalog().save("../evil", VALID))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("Nom de dataset invalide");
    }
}
