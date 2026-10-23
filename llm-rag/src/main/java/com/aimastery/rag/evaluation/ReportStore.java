package com.aimastery.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rapports d'evaluation RAG, un fichier JSON par execution (eval-reports/, hors git).
 *
 * Le rapport complet (recherche + generation) est produit par le portail, qui appelle aussi le
 * Gateway : llm-rag le stocke sans l'interpreter, pour que l'historique ne depende pas du
 * navigateur qui a lance l'evaluation.
 */
public class ReportStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final ObjectMapper json;

    public ReportStore(Path directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    /** Enregistre le rapport et lui donne un identifiant date ; renvoie le rapport tel que stocke. */
    public JsonNode save(ObjectNode report) {
        String id = "rag-" + ID_FORMAT.format(Instant.now());
        report.put("id", id);
        try {
            Files.createDirectories(directory);
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(id + ".json").toFile(), report);
            return report;
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du rapport impossible dans " + directory.toAbsolutePath(), e);
        }
    }

    /** Du plus recent au plus ancien ; chaque entree garde id, runAt, label et les metriques, sans le detail. */
    public List<JsonNode> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<JsonNode> summaries = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode report = read(file);
                ObjectNode summary = json.createObjectNode();
                for (String field : List.of("id", "runAt", "label", "topK", "scope", "provider", "model")) {
                    // Un rapport de recherche seule enregistre hors du portail n'a ces champs que dans "retrieval".
                    JsonNode value = report.has(field) ? report.get(field) : report.path("retrieval").get(field);
                    if (value != null) {
                        summary.set(field, value);
                    }
                }
                summary.set("retrieval", report.path("retrieval").path("metrics"));
                summary.set("index", report.path("retrieval").path("index"));
                summary.set("generation", report.path("generation").path("metrics"));
                summaries.add(summary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        summaries.sort(Comparator.comparing((JsonNode s) -> s.path("id").asText()).reversed());
        return summaries;
    }

    public Optional<JsonNode> find(String id) {
        // L'identifiant devient un nom de fichier : on refuse tout ce qui pourrait sortir du dossier.
        if (!SAFE_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path file = directory.resolve(id + ".json");
        return Files.exists(file) ? Optional.of(read(file)) : Optional.empty();
    }

    private JsonNode read(Path file) {
        try {
            return json.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Rapport illisible : " + file, e);
        }
    }
}
