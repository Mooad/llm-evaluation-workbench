package com.aimastery.gateway.eval.regression;

import com.aimastery.gateway.eval.Dimension;
import com.aimastery.gateway.eval.Verdict;
import java.util.List;
import java.util.Map;

/**
 * Comparaison de deux executions : reference (baseline) contre candidat.
 *
 * Tout est calcule sur les cas COMMUNS et identiques des deux cotes (meme id, meme empreinte),
 * en pourcentages : un dataset qui grossit ne fabrique ni fausse regression ni faux progres.
 * Les cas ajoutes, retires ou modifies sont listes a part, jamais melanges a la mesure.
 *
 * @param thresholdPoints baisse (en points de taux de reussite) au-dela de laquelle une
 *                        dimension est en regression ; en dessous, bruit de mesure
 * @param changes         cas dont un verdict a bascule, regressions en premier
 * @param file            ou ce rapport a ete ecrit
 */
public record RegressionReport(Ref baseline,
                               Ref candidate,
                               double thresholdPoints,
                               Status status,
                               String summary,
                               Coverage coverage,
                               Map<Dimension, DimensionDelta> dimensions,
                               CostComparison cost,
                               LatencyComparison latency,
                               List<CaseChange> changes,
                               List<String> warnings,
                               String file) {

    public enum Status { REGRESSION, IMPROVEMENT, STABLE, NOT_COMPARABLE }

    public record Ref(String id, String runId, String startedAt, String candidate, String judge,
                      String dataset, String datasetHash, int caseCount) {
    }

    /**
     * @param fingerprintsMissing un rapport ancien n'a pas d'empreinte : cas compares par id
     *                            seulement, une modification de cas passerait inapercue
     */
    public record Coverage(int common, List<String> added, List<String> removed, List<String> modified,
                           boolean fingerprintsMissing) {
    }

    /**
     * Taux sur les cas notes PASS/FAIL des DEUX cotes : meme sous-ensemble pour les deux taux.
     *
     * @param deltaPoints candidat - reference, en points de pourcentage
     */
    public record DimensionDelta(Double baselineRate, Double candidateRate, Double deltaPoints,
                                 int compared, int regressed, int improved, Status status) {
    }

    /**
     * Sur les cas communs. perCorrect = cout total / reponses CORRECTNESS PASS : un fournisseur
     * moins cher par appel mais souvent faux peut couter plus cher par bonne reponse.
     */
    public record CostComparison(String currency,
                                 Double baselineTotal, Double candidateTotal,
                                 Double baselinePerCase, Double candidatePerCase,
                                 Double baselinePerCorrect, Double candidatePerCorrect,
                                 Double deltaPerCorrectPercent) {
    }

    public record LatencyComparison(Long baselineP50, Long candidateP50,
                                    Long baselineAvg, Long candidateAvg,
                                    Double deltaP50Percent) {
    }

    public enum ChangeKind { REGRESSION, MIXED, IMPROVEMENT }

    public record CaseChange(String id, String category, ChangeKind kind, List<Transition> transitions) {
    }

    /** @param reason raison du verdict candidat : pourquoi il a bascule */
    public record Transition(Dimension dimension, Verdict from, Verdict to, String reason) {
    }
}
