package com.aimastery.gateway.eval.regression;

import com.aimastery.gateway.eval.CaseResult;
import com.aimastery.gateway.eval.Dimension;
import com.aimastery.gateway.eval.DimensionScore;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.Verdict;
import com.aimastery.gateway.eval.regression.RegressionReport.CaseChange;
import com.aimastery.gateway.eval.regression.RegressionReport.ChangeKind;
import com.aimastery.gateway.eval.regression.RegressionReport.CostComparison;
import com.aimastery.gateway.eval.regression.RegressionReport.Coverage;
import com.aimastery.gateway.eval.regression.RegressionReport.DimensionDelta;
import com.aimastery.gateway.eval.regression.RegressionReport.LatencyComparison;
import com.aimastery.gateway.eval.regression.RegressionReport.Ref;
import com.aimastery.gateway.eval.regression.RegressionReport.Status;
import com.aimastery.gateway.eval.regression.RegressionReport.Transition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Calcule l'etat de regression entre deux rapports. Pur : aucun appel, aucun fichier.
 */
@Component
public class RegressionAnalyzer {

    public RegressionReport compare(String baselineId, EvalReport baseline,
                                    String candidateId, EvalReport candidate,
                                    double thresholdPoints) {

        Map<String, CaseResult> before = byId(baseline);
        Map<String, CaseResult> after = byId(candidate);

        List<String> added = after.keySet().stream().filter(id -> !before.containsKey(id)).sorted().toList();
        List<String> removed = before.keySet().stream().filter(id -> !after.containsKey(id)).sorted().toList();
        List<String> modified = new ArrayList<>();
        List<String> common = new ArrayList<>();
        boolean fingerprintsMissing = false;
        for (String id : before.keySet()) {
            if (!after.containsKey(id)) {
                continue;
            }
            String hashBefore = before.get(id).caseHash();
            String hashAfter = after.get(id).caseHash();
            if (hashBefore == null || hashAfter == null) {
                fingerprintsMissing = true;
                common.add(id);
            } else if (hashBefore.equals(hashAfter)) {
                common.add(id);
            } else {
                // Le cas ne mesure plus la meme chose : le comparer fabriquerait un faux ecart.
                modified.add(id);
            }
        }
        common.sort(Comparator.naturalOrder());
        modified.sort(Comparator.naturalOrder());

        Map<Dimension, DimensionDelta> dimensions = new EnumMap<>(Dimension.class);
        for (Dimension d : Dimension.values()) {
            dimensions.put(d, delta(d, common, before, after, thresholdPoints));
        }
        Status status = overall(dimensions);

        List<CaseChange> changes = changes(common, before, after);
        CostComparison cost = cost(common, before, after, baseline.currency());
        LatencyComparison latency = latency(common, before, after);
        Coverage coverage = new Coverage(common.size(), added, removed, List.copyOf(modified), fingerprintsMissing);

        return new RegressionReport(
                ref(baselineId, baseline), ref(candidateId, candidate), thresholdPoints, status,
                summary(status, dimensions, changes, common.size(), thresholdPoints),
                coverage, dimensions, cost, latency, changes,
                warnings(baseline, candidate, coverage, cost), null);
    }

    private static DimensionDelta delta(Dimension d, List<String> common,
                                        Map<String, CaseResult> before, Map<String, CaseResult> after,
                                        double threshold) {
        int compared = 0;
        int passedBefore = 0;
        int passedAfter = 0;
        int regressed = 0;
        int improved = 0;
        for (String id : common) {
            Verdict from = before.get(id).verdict(d);
            Verdict to = after.get(id).verdict(d);
            // Un SKIPPED ou un ERROR d'un cote : rien a comparer pour cette dimension.
            if (!decided(from) || !decided(to)) {
                continue;
            }
            compared++;
            passedBefore += from == Verdict.PASS ? 1 : 0;
            passedAfter += to == Verdict.PASS ? 1 : 0;
            regressed += (from == Verdict.PASS && to == Verdict.FAIL) ? 1 : 0;
            improved += (from == Verdict.FAIL && to == Verdict.PASS) ? 1 : 0;
        }
        if (compared == 0) {
            return new DimensionDelta(null, null, null, 0, 0, 0, Status.NOT_COMPARABLE);
        }
        double rateBefore = (double) passedBefore / compared;
        double rateAfter = (double) passedAfter / compared;
        double points = round1((rateAfter - rateBefore) * 100);
        Status status = points < -threshold ? Status.REGRESSION
                : points > threshold ? Status.IMPROVEMENT
                : Status.STABLE;
        return new DimensionDelta(round3(rateBefore), round3(rateAfter), points, compared, regressed, improved, status);
    }

    private static Status overall(Map<Dimension, DimensionDelta> dimensions) {
        List<Status> statuses = dimensions.values().stream().map(DimensionDelta::status).toList();
        if (statuses.stream().allMatch(s -> s == Status.NOT_COMPARABLE)) {
            return Status.NOT_COMPARABLE;
        }
        if (statuses.contains(Status.REGRESSION)) {
            return Status.REGRESSION;
        }
        return statuses.contains(Status.IMPROVEMENT) ? Status.IMPROVEMENT : Status.STABLE;
    }

    private static List<CaseChange> changes(List<String> common,
                                            Map<String, CaseResult> before, Map<String, CaseResult> after) {
        List<CaseChange> changes = new ArrayList<>();
        for (String id : common) {
            List<Transition> transitions = new ArrayList<>();
            for (Dimension d : Dimension.values()) {
                Verdict from = before.get(id).verdict(d);
                Verdict to = after.get(id).verdict(d);
                if (decided(from) && decided(to) && from != to) {
                    transitions.add(new Transition(d, from, to, reason(after.get(id), d)));
                }
            }
            if (transitions.isEmpty()) {
                continue;
            }
            boolean anyDown = transitions.stream().anyMatch(t -> t.to() == Verdict.FAIL);
            boolean anyUp = transitions.stream().anyMatch(t -> t.to() == Verdict.PASS);
            ChangeKind kind = anyDown && anyUp ? ChangeKind.MIXED
                    : anyDown ? ChangeKind.REGRESSION : ChangeKind.IMPROVEMENT;
            changes.add(new CaseChange(id, after.get(id).category(), kind, transitions));
        }
        changes.sort(Comparator.comparing(CaseChange::kind).thenComparing(CaseChange::id));
        return changes;
    }

    private static CostComparison cost(List<String> common, Map<String, CaseResult> before,
                                       Map<String, CaseResult> after, String currency) {
        Double totalBefore = sumCost(common, before);
        Double totalAfter = sumCost(common, after);
        Double perCorrectBefore = perCorrect(totalBefore, common, before);
        Double perCorrectAfter = perCorrect(totalAfter, common, after);
        Double delta = (perCorrectBefore == null || perCorrectAfter == null || perCorrectBefore == 0)
                ? null
                : round1((perCorrectAfter - perCorrectBefore) / perCorrectBefore * 100);
        return new CostComparison(currency, totalBefore, totalAfter,
                perCase(totalBefore, common.size()), perCase(totalAfter, common.size()),
                perCorrectBefore, perCorrectAfter, delta);
    }

    private static LatencyComparison latency(List<String> common, Map<String, CaseResult> before,
                                             Map<String, CaseResult> after) {
        List<Long> latBefore = latencies(common, before);
        List<Long> latAfter = latencies(common, after);
        Long p50Before = p50(latBefore);
        Long p50After = p50(latAfter);
        Double delta = (p50Before == null || p50After == null || p50Before == 0) ? null
                : round1((p50After - p50Before) * 100.0 / p50Before);
        return new LatencyComparison(p50Before, p50After, avg(latBefore), avg(latAfter), delta);
    }

    private static List<String> warnings(EvalReport baseline, EvalReport candidate, Coverage coverage,
                                         CostComparison cost) {
        List<String> warnings = new ArrayList<>();
        if (coverage.common() == 0) {
            warnings.add("Aucun cas commun : les deux rapports n'evaluent pas les memes questions.");
        }
        if (!Objects.equals(baseline.judge(), candidate.judge())) {
            warnings.add("Juges differents (" + baseline.judge() + " / " + candidate.judge()
                    + ") : les verdicts rendus par le juge ne sont pas strictement comparables.");
        }
        if (baseline.settings() != null && candidate.settings() != null
                && (baseline.settings().temperature() != candidate.settings().temperature()
                || baseline.settings().maxTokens() != candidate.settings().maxTokens())) {
            warnings.add("Reglages differents (temperature ou maxTokens) : une partie de l'ecart peut en venir.");
        }
        String promptBefore = baseline.settings() == null ? null : baseline.settings().systemPrompt();
        String promptAfter = candidate.settings() == null ? null : candidate.settings().systemPrompt();
        if (!Objects.equals(promptBefore, promptAfter)) {
            // Pas une erreur : c'est souvent exactement ce qu'on veut mesurer.
            warnings.add("Prompt systeme different entre les deux executions : l'ecart mesure aussi son effet.");
        }
        if (coverage.fingerprintsMissing()) {
            warnings.add("Rapport sans empreinte de cas (ancien format) : cas compares par id seulement.");
        }
        if (!coverage.modified().isEmpty()) {
            warnings.add(coverage.modified().size() + " cas modifies entre les deux rapports : exclus de la comparaison.");
        }
        if (Objects.equals(cost.baselineTotal(), 0.0) || Objects.equals(cost.candidateTotal(), 0.0)) {
            warnings.add("Cout 0 = modele local : aucune facture d'API, mais l'energie n'est pas comptee.");
        }
        return warnings;
    }

    private static String summary(Status status, Map<Dimension, DimensionDelta> dimensions,
                                  List<CaseChange> changes, int common, double threshold) {
        if (status == Status.NOT_COMPARABLE) {
            return "Non comparable : aucun cas commun note des deux cotes.";
        }
        String rates = dimensions.entrySet().stream()
                .filter(e -> e.getValue().status() != Status.NOT_COMPARABLE)
                .map(e -> e.getKey() + " " + pct(e.getValue().baselineRate()) + " -> "
                        + pct(e.getValue().candidateRate()) + " (" + signed(e.getValue().deltaPoints()) + " pts)")
                .collect(Collectors.joining(", "));
        long down = changes.stream().filter(c -> c.kind() != ChangeKind.IMPROVEMENT).count();
        long up = changes.stream().filter(c -> c.kind() != ChangeKind.REGRESSION).count();
        String label = switch (status) {
            case REGRESSION -> "Regression";
            case IMPROVEMENT -> "Amelioration";
            default -> "Stable";
        };
        return label + " sur " + common + " cas communs (seuil " + threshold + " pts) : " + rates
                + ". " + down + " cas en baisse, " + up + " en hausse.";
    }

    // --- utilitaires ------------------------------------------------------------------

    private static Map<String, CaseResult> byId(EvalReport report) {
        return report.cases().stream().collect(Collectors.toMap(CaseResult::id, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    private static Ref ref(String id, EvalReport r) {
        return new Ref(id, r.runId(), r.startedAt(), r.candidate(), r.judge(), r.dataset(), r.datasetHash(),
                r.caseCount());
    }

    private static boolean decided(Verdict v) {
        return v == Verdict.PASS || v == Verdict.FAIL;
    }

    private static String reason(CaseResult result, Dimension d) {
        return result.scores().stream().filter(s -> s.dimension() == d)
                .map(DimensionScore::reason).findFirst().orElse(null);
    }

    private static Double sumCost(List<String> ids, Map<String, CaseResult> cases) {
        List<Double> costs = ids.stream().map(id -> cases.get(id).cost()).filter(Objects::nonNull).toList();
        return costs.isEmpty() ? null : costs.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static Double perCorrect(Double total, List<String> ids, Map<String, CaseResult> cases) {
        long correct = ids.stream().filter(id -> cases.get(id).verdict(Dimension.CORRECTNESS) == Verdict.PASS).count();
        return (total == null || correct == 0) ? null : total / correct;
    }

    private static Double perCase(Double total, int count) {
        return (total == null || count == 0) ? null : total / count;
    }

    private static List<Long> latencies(List<String> ids, Map<String, CaseResult> cases) {
        return ids.stream().map(cases::get)
                .filter(r -> r.latencyMs() != null && r.error() == null)
                .map(CaseResult::latencyMs).sorted().toList();
    }

    private static Long p50(List<Long> sorted) {
        return sorted.isEmpty() ? null : sorted.get(sorted.size() / 2);
    }

    private static Long avg(List<Long> values) {
        return values.isEmpty() ? null : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static String pct(Double rate) {
        return rate == null ? "-" : String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    private static String signed(Double points) {
        return points == null ? "-" : String.format(Locale.ROOT, "%+.1f", points);
    }
}
