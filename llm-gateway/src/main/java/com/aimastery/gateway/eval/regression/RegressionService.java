package com.aimastery.gateway.eval.regression;

import com.aimastery.gateway.eval.EvalProperties;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.ReportStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * Compare deux rapports et ecrit le rapport de regression a cote d'eux, en JSON :
 * c'est lui qu'on garde, qu'on partage et qu'on relit, pas l'ecran.
 */
@Service
public class RegressionService {

    private final ReportStore store;
    private final RegressionAnalyzer analyzer;
    private final ObjectMapper mapper;
    private final double defaultThreshold;

    public RegressionService(ReportStore store, RegressionAnalyzer analyzer, ObjectMapper mapper,
                             EvalProperties properties) {
        this.store = store;
        this.analyzer = analyzer;
        this.mapper = mapper;
        this.defaultThreshold = properties.regressionThresholdPoints();
    }

    public RegressionReport compare(String baselineId, String candidateId, Double thresholdPoints) {
        EvalReport baseline = store.load(baselineId);
        EvalReport candidate = store.load(candidateId);
        double threshold = thresholdPoints == null ? defaultThreshold : thresholdPoints;

        RegressionReport report = analyzer.compare(baselineId, baseline, candidateId, candidate, threshold);
        Path file = store.regressionsDir().resolve(
                "regression-" + baseline.runId() + "-vs-" + candidate.runId() + ".json");
        RegressionReport saved = withFile(report, file.toAbsolutePath().toString());
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), saved);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du rapport de regression impossible : " + file, e);
        }
        return saved;
    }

    private static RegressionReport withFile(RegressionReport r, String file) {
        return new RegressionReport(r.baseline(), r.candidate(), r.thresholdPoints(), r.status(), r.summary(),
                r.coverage(), r.dimensions(), r.cost(), r.latency(), r.changes(), r.warnings(), file);
    }
}
