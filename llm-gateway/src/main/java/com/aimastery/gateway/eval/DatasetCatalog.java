package com.aimastery.gateway.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Les datasets evaluables : le golden dataset integre, plus ceux importes depuis le portail
 * (un fichier JSON par dataset dans gateway.eval.datasets-dir).
 */
@Component
public class DatasetCatalog {

    public static final String BUILT_IN = "golden";

    /** Le nom devient un nom de fichier : rien qui puisse sortir du repertoire. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    private final EvalDatasetLoader loader;
    private final EvalProperties properties;
    private final Path dir;

    public DatasetCatalog(EvalDatasetLoader loader, EvalProperties properties) {
        this.loader = loader;
        this.properties = properties;
        this.dir = Path.of(properties.datasetsDir());
    }

    public List<DatasetSummary> list() {
        List<DatasetSummary> all = new ArrayList<>();
        all.add(summary(BUILT_IN, properties.datasetPath(), true));
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(f -> all.add(summary(idOf(f), "file:" + f.toAbsolutePath(), false)));
            } catch (IOException e) {
                throw new UncheckedIOException("Lecture de " + dir + " impossible", e);
            }
        }
        return all;
    }

    public List<EvalCase> cases(String id) {
        return loader.load(location(id));
    }

    /** Emplacement chargeable par EvalDatasetLoader (classpath:... ou file:...). */
    public String location(String id) {
        if (id == null || id.isBlank() || BUILT_IN.equals(id)) {
            return properties.datasetPath();
        }
        Path file = file(id);
        if (!Files.exists(file)) {
            throw new EvalDatasetException("Dataset inconnu : '" + id + "'");
        }
        return "file:" + file.toAbsolutePath();
    }

    /** Inverse de location, pour retrouver le dataset d'un rapport. */
    public String idOf(String location) {
        if (location == null || location.equals(properties.datasetPath())) {
            return BUILT_IN;
        }
        if (location.startsWith("file:")) {
            Path file = Path.of(location.substring("file:".length()));
            if (dir.toAbsolutePath().equals(file.getParent())) {
                return idOf(file);
            }
        }
        return location;
    }

    /** Valide comme au chargement, puis ecrit : un dataset invalide n'atterrit jamais sur disque. */
    public DatasetSummary save(String id, String json) {
        if (BUILT_IN.equals(id)) {
            throw new DatasetAlreadyExistsException(id);
        }
        Path file = file(id);
        if (Files.exists(file)) {
            throw new DatasetAlreadyExistsException(id);
        }
        loader.parse(json, id);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture de " + file + " impossible", e);
        }
        return summary(id, "file:" + file.toAbsolutePath(), false);
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new EvalDatasetException("Nom de dataset invalide : '" + id
                    + "' (minuscules, chiffres, - et _, 48 caracteres au plus)");
        }
        return dir.resolve(id + ".json");
    }

    private DatasetSummary summary(String id, String location, boolean builtIn) {
        try {
            List<EvalCase> cases = loader.load(location);
            Map<String, Integer> categories = new TreeMap<>();
            cases.forEach(c -> categories.merge(c.category() == null ? "-" : c.category(), 1, Integer::sum));
            int schemaCases = (int) cases.stream().filter(EvalCase::hasSchema).count();
            return new DatasetSummary(id, builtIn, cases.size(), categories, schemaCases,
                    CaseFingerprint.ofDataset(cases), null);
        } catch (EvalDatasetException e) {
            return new DatasetSummary(id, builtIn, 0, Map.of(), 0, null, e.getMessage());
        }
    }

    private static String idOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }
}
