package com.aimastery.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportStoreTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void un_rapport_enregistre_se_relit_et_apparait_dans_la_liste_sans_son_detail(@TempDir Path dir) {
        ReportStore store = new ReportStore(dir, json);
        ObjectNode report = json.createObjectNode().put("label", "Avant sections");
        report.putObject("retrieval").putObject("metrics").put("recallAt1", 0.875);
        report.withObject("/retrieval").putArray("cases").addObject().put("id", "q1");

        String id = store.save(report).get("id").asText();

        assertThat(store.find(id)).get().extracting(r -> r.get("label").asText()).isEqualTo("Avant sections");
        JsonNode summary = store.list().get(0);
        assertThat(summary.get("retrieval").get("recallAt1").asDouble()).isEqualTo(0.875);
        assertThat(summary.has("cases")).isFalse();
    }

    @Test
    void un_identifiant_qui_sortirait_du_dossier_est_refuse(@TempDir Path dir) {
        assertThat(new ReportStore(dir, json).find("../../etc/passwd")).isEmpty();
    }
}
