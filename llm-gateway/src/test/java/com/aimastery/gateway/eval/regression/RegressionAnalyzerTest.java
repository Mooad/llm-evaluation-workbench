package com.aimastery.gateway.eval.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.eval.CaseResult;
import com.aimastery.gateway.eval.Dimension;
import com.aimastery.gateway.eval.DimensionScore;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.Verdict;
import com.aimastery.gateway.eval.regression.RegressionReport.ChangeKind;
import com.aimastery.gateway.eval.regression.RegressionReport.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegressionAnalyzerTest {

    private final RegressionAnalyzer analyzer = new RegressionAnalyzer();

    @Test
    void une_baisse_au_dela_du_seuil_est_une_regression() {
        EvalReport before = report(pass("a"), pass("b"), pass("c"), pass("d"));
        EvalReport after = report(pass("a"), pass("b"), pass("c"), fail("d"));

        RegressionReport r = compare(before, after, 5.0);

        assertThat(r.status()).isEqualTo(Status.REGRESSION);
        assertThat(r.dimensions().get(Dimension.CORRECTNESS).deltaPoints()).isEqualTo(-25.0);
        assertThat(r.changes()).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(ChangeKind.REGRESSION));
    }

    @Test
    void une_baisse_sous_le_seuil_reste_stable_mais_les_cas_en_baisse_restent_listes() {
        List<CaseResult> before = new ArrayList<>();
        List<CaseResult> after = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            before.add(pass("q" + i));
            after.add(i == 0 ? fail("q" + i) : pass("q" + i));
        }

        RegressionReport r = compare(report(before), report(after), 5.0);

        // -2,5 pts : bruit de mesure au seuil de 5, mais le cas qui a bascule ne doit pas disparaitre.
        assertThat(r.status()).isEqualTo(Status.STABLE);
        assertThat(r.changes()).extracting(RegressionReport.CaseChange::id).containsExactly("q0");
    }

    @Test
    void un_dataset_qui_grossit_ne_fabrique_pas_de_fausse_regression() {
        EvalReport before = report(pass("a"), pass("b"));
        // Deux nouveaux cas, echoues : ils ne doivent pas faire baisser le taux compare.
        EvalReport after = report(pass("a"), pass("b"), fail("nouveau1"), fail("nouveau2"));

        RegressionReport r = compare(before, after, 5.0);

        assertThat(r.status()).isEqualTo(Status.STABLE);
        assertThat(r.coverage().common()).isEqualTo(2);
        assertThat(r.coverage().added()).containsExactly("nouveau1", "nouveau2");
        assertThat(r.dimensions().get(Dimension.CORRECTNESS).candidateRate()).isEqualTo(1.0);
    }

    @Test
    void un_cas_modifie_entre_deux_rapports_est_exclu_de_la_comparaison() {
        EvalReport before = report(pass("a"), withHash(pass("b"), "v1"));
        EvalReport after = report(pass("a"), withHash(fail("b"), "v2"));

        RegressionReport r = compare(before, after, 5.0);

        assertThat(r.coverage().modified()).containsExactly("b");
        assertThat(r.status()).isEqualTo(Status.STABLE);
        assertThat(r.warnings()).anyMatch(w -> w.contains("modifies"));
    }

    @Test
    void un_cas_non_note_d_un_cote_ne_compte_pas_dans_le_taux() {
        EvalReport before = report(pass("a"), pass("b"));
        EvalReport after = report(pass("a"), result("b", Verdict.ERROR, 0.0));

        DimensionDeltaAssert.of(compare(before, after, 5.0))
                .compared(1).rates(1.0, 1.0);
    }

    @Test
    void compare_le_cout_par_bonne_reponse_pas_seulement_le_cout_total() {
        // Reference : 4 appels a 0,001, 4 bonnes reponses. Candidat : moitie moins cher, 1 seule bonne.
        EvalReport before = report(result("a", Verdict.PASS, 0.001), result("b", Verdict.PASS, 0.001),
                result("c", Verdict.PASS, 0.001), result("d", Verdict.PASS, 0.001));
        EvalReport after = report(result("a", Verdict.PASS, 0.0005), result("b", Verdict.FAIL, 0.0005),
                result("c", Verdict.FAIL, 0.0005), result("d", Verdict.FAIL, 0.0005));

        RegressionReport.CostComparison cost = compare(before, after, 5.0).cost();

        assertThat(cost.candidateTotal()).isLessThan(cost.baselineTotal());
        assertThat(cost.baselinePerCorrect()).isEqualTo(0.001);
        assertThat(cost.candidatePerCorrect()).isEqualTo(0.002);
        assertThat(cost.deltaPerCorrectPercent()).isEqualTo(100.0);
    }

    @Test
    void signale_que_les_deux_executions_n_ont_pas_le_meme_prompt_systeme() {
        EvalReport before = withPrompt(report(pass("a")), null);
        EvalReport after = withPrompt(report(pass("a")), "Dis que tu ne sais pas si tu n'es pas sur.");

        assertThat(compare(before, after, 5.0).warnings()).anyMatch(w -> w.contains("Prompt systeme different"));
    }

    @Test
    void sans_cas_commun_la_comparaison_est_impossible() {
        RegressionReport r = compare(report(pass("a")), report(pass("b")), 5.0);

        assertThat(r.status()).isEqualTo(Status.NOT_COMPARABLE);
        assertThat(r.warnings()).anyMatch(w -> w.contains("Aucun cas commun"));
    }

    // --- fabrication de rapports ----------------------------------------------------

    private RegressionReport compare(EvalReport before, EvalReport after, double threshold) {
        return analyzer.compare("base", before, "cand", after, threshold);
    }

    private static CaseResult pass(String id) {
        return result(id, Verdict.PASS, 0.0);
    }

    private static CaseResult fail(String id) {
        return result(id, Verdict.FAIL, 0.0);
    }

    /** Seule CORRECTNESS est notee : les autres dimensions sont SKIPPED. */
    private static CaseResult result(String id, Verdict correctness, double cost) {
        List<DimensionScore> scores = List.of(
                new DimensionScore(Dimension.CORRECTNESS, correctness, "regle", "raison " + id),
                DimensionScore.skipped(Dimension.FORMAT, "-"),
                DimensionScore.skipped(Dimension.HALLUCINATION, "-"));
        return new CaseResult(id, "h-" + id, "factual", "prompt", "reponse", "p", false, "m", 100L,
                10, 5, cost, null, null, scores, null);
    }

    private static CaseResult withHash(CaseResult r, String hash) {
        return new CaseResult(r.id(), hash, r.category(), r.prompt(), r.answer(), r.servedBy(),
                r.fallbackUsed(), r.model(), r.latencyMs(), r.inputTokens(), r.outputTokens(), r.cost(),
                r.finishReason(), r.schemaValid(), r.scores(), r.error());
    }

    private static EvalReport report(CaseResult... cases) {
        return report(Arrays.asList(cases));
    }

    private static EvalReport report(List<CaseResult> cases) {
        return new EvalReport("run", "2026-10-05T10:00:00Z", 1000, "p/m", "desactive", "golden", "hash",
                null, cases.size(), Map.of(), Map.of(), null, 0, 0, null, null, "USD", List.of(), cases, null);
    }

    private static EvalReport withPrompt(EvalReport r, String prompt) {
        return new EvalReport(r.runId(), r.startedAt(), r.durationMs(), r.candidate(), r.judge(), r.dataset(),
                r.datasetHash(), new EvalReport.RunSettings(0.0, 512, false, false, false, prompt), r.caseCount(),
                r.dimensions(), r.schemaConformity(), r.latency(), r.inputTokens(), r.outputTokens(), r.totalCost(),
                r.costPerCorrect(), r.currency(), r.warnings(), r.cases(), r.reportFile());
    }

    private record DimensionDeltaAssert(RegressionReport.DimensionDelta delta) {
        static DimensionDeltaAssert of(RegressionReport r) {
            return new DimensionDeltaAssert(r.dimensions().get(Dimension.CORRECTNESS));
        }

        DimensionDeltaAssert compared(int expected) {
            assertThat(delta.compared()).isEqualTo(expected);
            return this;
        }

        void rates(double before, double after) {
            assertThat(delta.baselineRate()).isEqualTo(before);
            assertThat(delta.candidateRate()).isEqualTo(after);
        }
    }
}
