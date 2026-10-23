package com.aimastery.gateway.config;

import com.aimastery.gateway.core.GenerationDefaults;
import com.aimastery.gateway.core.GenerationDefaultsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Defauts globaux par fournisseur, dans un fichier JSON modifiable depuis le portail.
 *
 * Un fichier plutot que application.yml : le portail le reecrit, et reecrire le yml
 * effacerait ses commentaires. C'est de la configuration, pas de l'etat de session :
 * le Gateway reste sans memoire.
 */
@Component
public class FileGenerationDefaults implements GenerationDefaultsSource {

    private static final Logger log = LoggerFactory.getLogger(FileGenerationDefaults.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private volatile Map<String, GenerationDefaults> byProvider;

    public FileGenerationDefaults(SettingsProperties properties) {
        this.file = Path.of(properties.file());
        this.byProvider = load();
    }

    @Override
    public GenerationDefaults forProvider(String provider) {
        return byProvider.getOrDefault(provider, GenerationDefaults.NONE);
    }

    public Map<String, GenerationDefaults> all() {
        return byProvider;
    }

    public Path file() {
        return file;
    }

    /** Remplace les defauts d'un fournisseur et reecrit le fichier. */
    public synchronized GenerationDefaults update(String provider, GenerationDefaults defaults) {
        Map<String, GenerationDefaults> next = new TreeMap<>(byProvider);
        next.put(provider, defaults);
        write(next);
        byProvider = Collections.unmodifiableMap(next);
        log.info("defauts globaux de '{}' mis a jour dans {} : {}", provider, file, defaults);
        return defaults;
    }

    private Map<String, GenerationDefaults> load() {
        if (!Files.exists(file)) {
            log.info("aucun fichier de defauts ({}) : chaque fournisseur garde ses propres defauts", file);
            return Map.of();
        }
        try {
            SettingsFile content = mapper.readValue(file.toFile(), SettingsFile.class);
            return content.providers() == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(content.providers()));
        } catch (IOException e) {
            // Echouer au demarrage : des defauts ignores en silence fausseraient chaque appel.
            throw new IllegalStateException("Fichier de defauts illisible : " + file + " — " + e.getMessage(), e);
        }
    }

    /** Ecriture dans un fichier temporaire puis deplacement : jamais de fichier a moitie ecrit. */
    private void write(Map<String, GenerationDefaults> content) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "defaults", ".json.tmp");
            mapper.writeValue(tmp.toFile(), new SettingsFile(content));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture de " + file + " impossible", e);
        }
    }

    record SettingsFile(Map<String, GenerationDefaults> providers) {
    }
}
