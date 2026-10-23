package com.aimastery.gateway.eval.gate;

import com.aimastery.gateway.eval.EvalProperties;
import com.aimastery.gateway.eval.EvalReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Les rapports de reference du test de non-regression, un par (dataset, candidat).
 *
 * Copies versionnees dans git (eval-baselines/), contrairement a eval-reports/ : la reference
 * doit survivre a un nettoyage et voyager avec le code qu'elle valide.
 */
@Component
public class BaselineStore {

    private final Path dir;
    private final ObjectMapper mapper;

    public BaselineStore(EvalProperties properties, ObjectMapper mapper) {
        this.dir = Path.of(properties.baselinesDir());
        this.mapper = mapper;
    }

    public Optional<EvalReport> load(String datasetId, String candidate) {
        Path file = file(datasetId, candidate);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), EvalReport.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Reference illisible : " + file, e);
        }
    }

    public Path save(String datasetId, EvalReport report) {
        Path file = file(datasetId, report.candidate());
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture de la reference impossible : " + file, e);
        }
    }

    /** "golden--ollama_qwen3-coder_30b.json" : lisible, et sans caractere hors du repertoire. */
    Path file(String datasetId, String candidate) {
        return dir.resolve(datasetId + "--" + candidate.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }
}
