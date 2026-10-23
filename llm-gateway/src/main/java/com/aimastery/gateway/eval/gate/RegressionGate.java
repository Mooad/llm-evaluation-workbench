package com.aimastery.gateway.eval.gate;

import com.aimastery.gateway.eval.DatasetCatalog;
import com.aimastery.gateway.eval.EvalProperties;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.EvalRun;
import com.aimastery.gateway.eval.EvalRunner;
import com.aimastery.gateway.eval.ReportStore;
import com.aimastery.gateway.eval.gate.GateResult.Outcome;
import com.aimastery.gateway.eval.regression.RegressionAnalyzer;
import com.aimastery.gateway.eval.regression.RegressionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Test de non-regression : rejouer le dataset, comparer a la reference, conclure.
 *
 * Meme calcul que l'ecran Evaluations (RegressionAnalyzer) : un seul etat de regression,
 * qu'on le regarde a l'ecran ou qu'il fasse echouer une commande.
 */
@Service
public class RegressionGate {

    private final EvalRunner runner;
    private final DatasetCatalog catalog;
    private final BaselineStore baselines;
    private final RegressionAnalyzer analyzer;
    private final ReportStore reports;
    private final ObjectMapper mapper;
    private final double defaultThreshold;

    public RegressionGate(EvalRunner runner, DatasetCatalog catalog, BaselineStore baselines,
                          RegressionAnalyzer analyzer, ReportStore reports, ObjectMapper mapper,
                          EvalProperties properties) {
        this.runner = runner;
        this.catalog = catalog;
        this.baselines = baselines;
        this.analyzer = analyzer;
        this.reports = reports;
        this.mapper = mapper;
        this.defaultThreshold = properties.regressionThresholdPoints();
    }

    public GateResult run(GateProperties gate) {
        // Temperature 0, pas de repli : on mesure le candidat, et de facon reproductible.
        EvalRun run = new EvalRun(gate.provider(), gate.model(), catalog.location(gate.dataset()), null,
                gate.useJudge(), gate.judgeProvider(), gate.judgeModel(), 0.0, 512, false);
        EvalReport fresh = runner.run(run);
        String freshId = ReportStore.idOf(fresh.reportFile());

        Optional<EvalReport> baseline = baselines.load(gate.dataset(), fresh.candidate());
        if (baseline.isEmpty()) {
            if (gate.promote()) {
                Path file = baselines.save(gate.dataset(), fresh);
                return new GateResult(Outcome.BASELINE_CREATED,
                        "Reference creee pour " + fresh.candidate() + " : " + file, null, fresh.reportFile());
            }
            return new GateResult(Outcome.NO_BASELINE, "Aucune reference pour " + fresh.candidate()
                    + " sur '" + gate.dataset() + "' : relancer avec --promote pour la creer.", null, fresh.reportFile());
        }

        double threshold = gate.thresholdPoints() == null ? defaultThreshold : gate.thresholdPoints();
        RegressionReport regression = write(analyzer.compare("reference", baseline.get(), freshId, fresh, threshold));

        boolean failed = regression.status() == RegressionReport.Status.REGRESSION
                || regression.status() == RegressionReport.Status.NOT_COMPARABLE;
        if (!failed && gate.promote()) {
            baselines.save(gate.dataset(), fresh);
        }
        return new GateResult(failed ? Outcome.FAILED : Outcome.PASSED, regression.summary(), regression,
                fresh.reportFile());
    }

    private RegressionReport write(RegressionReport r) {
        Path file = reports.regressionsDir().resolve(
                "gate-" + r.baseline().runId() + "-vs-" + r.candidate().runId() + ".json");
        RegressionReport saved = new RegressionReport(r.baseline(), r.candidate(), r.thresholdPoints(), r.status(),
                r.summary(), r.coverage(), r.dimensions(), r.cost(), r.latency(), r.changes(), r.warnings(),
                file.toAbsolutePath().toString());
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), saved);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du rapport de regression impossible : " + file, e);
        }
        return saved;
    }
}
