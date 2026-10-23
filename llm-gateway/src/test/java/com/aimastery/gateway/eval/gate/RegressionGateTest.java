package com.aimastery.gateway.eval.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.eval.DatasetCatalog;
import com.aimastery.gateway.eval.EvalDatasetLoader;
import com.aimastery.gateway.eval.EvalFixtures;
import com.aimastery.gateway.eval.EvalProperties;
import com.aimastery.gateway.eval.ReportStore;
import com.aimastery.gateway.eval.gate.GateResult.Outcome;
import com.aimastery.gateway.eval.regression.RegressionAnalyzer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class RegressionGateTest {

    @TempDir
    Path dir;

    /** Regles seules, seuil 1 pt : un seul cas qui bascule suffit a etre une regression. */
    private static final GateProperties GATE = new GateProperties(true, "golden", "local", null,
            false, null, null, 1.0, false);
    private static final GateProperties PROMOTE = new GateProperties(true, "golden", "local", null,
            false, null, null, 1.0, true);

    private RegressionGate gate(LlmProvider provider) {
        EvalProperties properties = EvalFixtures.properties(dir.resolve("reports"), dir.resolve("datasets"));
        DatasetCatalog catalog = new DatasetCatalog(
                new EvalDatasetLoader(new DefaultResourceLoader(), EvalFixtures.MAPPER), properties);
        return new RegressionGate(EvalFixtures.runner(properties, provider), catalog,
                new BaselineStore(properties, EvalFixtures.MAPPER), new RegressionAnalyzer(),
                new ReportStore(properties, EvalFixtures.MAPPER, catalog), EvalFixtures.MAPPER, properties);
    }

    @Test
    void sans_reference_le_test_ne_conclut_pas() {
        assertThat(gate(EvalFixtures.constant("local", "Rabat")).run(GATE).outcome()).isEqualTo(Outcome.NO_BASELINE);
    }

    @Test
    void la_premiere_execution_promue_devient_la_reference() {
        GateResult result = gate(EvalFixtures.constant("local", "Rabat")).run(PROMOTE);

        assertThat(result.outcome()).isEqualTo(Outcome.BASELINE_CREATED);
        assertThat(dir.resolve("baselines/golden--local_local-model.json")).exists();
    }

    @Test
    void la_meme_reponse_qu_a_la_reference_passe() {
        gate(EvalFixtures.constant("local", "Rabat")).run(PROMOTE);

        GateResult result = gate(EvalFixtures.constant("local", "Rabat")).run(GATE);

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.outcome().exitCode()).isZero();
    }

    @Test
    void une_reponse_qui_se_degrade_fait_echouer_le_test() {
        gate(EvalFixtures.constant("local", "Rabat")).run(PROMOTE);

        // "Rabat" reussissait fact_001 ; "Casablanca" le rate : regression.
        GateResult result = gate(EvalFixtures.constant("local", "Casablanca")).run(GATE);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.outcome().exitCode()).isEqualTo(1);
        assertThat(result.regression().changes()).anyMatch(c -> c.id().equals("fact_001"));
    }

    @Test
    void une_regression_ne_remplace_jamais_la_reference_meme_avec_promote() {
        gate(EvalFixtures.constant("local", "Rabat")).run(PROMOTE);
        gate(EvalFixtures.constant("local", "Casablanca")).run(PROMOTE);

        // La reference est restee "Rabat" : la meme reponse passe toujours.
        assertThat(gate(EvalFixtures.constant("local", "Rabat")).run(GATE).outcome()).isEqualTo(Outcome.PASSED);
    }
}
