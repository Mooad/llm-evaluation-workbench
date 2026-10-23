package com.aimastery.gateway.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Les rapports d'evaluation, un fichier JSON par execution dans gateway.eval.reports-dir.
 * Le fichier reste la source de verite : pas de base, rien a migrer, lisible a la main.
 */
@Component
public class ReportStore {

    private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

    /** L'id devient un chemin : rien qui puisse sortir du repertoire des rapports. */
    private static final Pattern ID = Pattern.compile("eval-[A-Za-z0-9._-]+");

    private final Path dir;
    private final ObjectMapper mapper;
    private final DatasetCatalog catalog;

    public ReportStore(EvalProperties properties, ObjectMapper mapper, DatasetCatalog catalog) {
        this.dir = Path.of(properties.reportsDir());
        this.mapper = mapper;
        this.catalog = catalog;
    }

    /** Du plus recent au plus ancien. Un fichier illisible est ignore, pas fatal. */
    public List<ReportSummary> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> ID.matcher(stem(f)).matches() && f.toString().endsWith(".json"))
                    .map(this::readSummary)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ReportSummary::startedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de " + dir + " impossible", e);
        }
    }

    public EvalReport load(String id) {
        try {
            return mapper.readValue(file(id).toFile(), EvalReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Rapport illisible : " + id, e);
        }
    }

    public void delete(String id) {
        try {
            Files.delete(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Suppression de " + id + " impossible", e);
        }
    }

    public Path regressionsDir() {
        return dir.resolve("regressions");
    }

    public static String idOf(String reportFile) {
        return reportFile == null ? null : stem(Path.of(reportFile));
    }

    public ReportSummary summarize(String id, EvalReport r) {
        int schemaCases = 0;
        int conform = 0;
        if (r.schemaConformity() != null) {
            for (EvalReport.SchemaStats s : r.schemaConformity().values()) {
                schemaCases += s.cases();
                conform += s.conform();
            }
        }
        return new ReportSummary(id, r.runId(), r.startedAt(), r.durationMs(), r.candidate(), r.judge(),
                catalog.idOf(r.dataset()), r.datasetHash(), r.settings() != null && r.settings().filtered(),
                r.caseCount(), r.dimensions(), r.latency(), r.totalCost(), costPerCorrect(r), r.currency(),
                schemaCases == 0 ? null : Math.round(1000.0 * conform / schemaCases) / 1000.0,
                r.warnings() == null ? 0 : r.warnings().size());
    }

    /** Absent des rapports anterieurs, mais deductible de ce qu'ils contiennent. */
    private static Double costPerCorrect(EvalReport r) {
        if (r.costPerCorrect() != null) {
            return r.costPerCorrect();
        }
        EvalReport.DimensionStats correctness = r.dimensions() == null ? null : r.dimensions().get(Dimension.CORRECTNESS);
        return (r.totalCost() == null || correctness == null || correctness.passed() == 0)
                ? null
                : r.totalCost() / correctness.passed();
    }

    private ReportSummary readSummary(Path file) {
        String id = stem(file);
        try {
            return summarize(id, mapper.readValue(file.toFile(), EvalReport.class));
        } catch (IOException e) {
            log.warn("rapport ignore, illisible : {} ({})", file, e.getMessage());
            return null;
        }
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new ReportNotFoundException(id);
        }
        Path file = dir.resolve(id + ".json");
        if (!Files.exists(file)) {
            throw new ReportNotFoundException(id);
        }
        return file;
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
    }
}
