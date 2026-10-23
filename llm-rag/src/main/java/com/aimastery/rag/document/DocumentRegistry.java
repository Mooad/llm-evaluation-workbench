package com.aimastery.rag.document;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registre des documents, garde en memoire et recopie dans un fichier JSON a chaque changement.
 *
 * Le fichier survit au redemarrage : l'IHM retrouve la liste des documents sans reimport, et un
 * PDF deja indexe est reconnu sans recalculer ses embeddings. Un fichier JSON suffit en local
 * (quelques dizaines de documents, un seul processus) ; une base viendra si le service est partage.
 */
public class DocumentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentRegistry.class);
    static final String INTERRUPTED = "Indexation interrompue par un redemarrage du service : reimporter ou reconstruire l'index";

    // Mapper propre au registre : le format du fichier ne doit pas changer avec la config web de Spring.
    // Champ inconnu ignore : un champ retire du record ne doit pas empecher de relire un ancien registre.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path file;
    private final String legacyIndex;
    private final Map<String, DocumentRecord> records = new ConcurrentHashMap<>();

    /**
     * @param legacyIndex index auquel rattacher les documents d'un registre ecrit avant les index
     *                    multiples : leurs chunks sont dans la collection de cet index
     */
    public DocumentRegistry(Path file, String legacyIndex) {
        this.file = file;
        this.legacyIndex = legacyIndex;
        load();
    }

    /**
     * Reserve l'indexation d'un document dans les index demandes, et dit lesquels sont a faire.
     *
     * Un index est a faire s'il n'a jamais ete fait ou a echoue ; avec force, aussi s'il est pret.
     * Jamais s'il est en cours : deux indexations du meme document s'ecraseraient.
     * synchronized : deux envois simultanes du meme PDF ne lancent qu'une indexation.
     */
    public synchronized Claim claim(DocumentRecord candidate, Collection<String> indexes, boolean force) {
        DocumentRecord current = records.getOrDefault(candidate.documentId(), candidate);
        List<String> todo = new ArrayList<>();
        for (String index : indexes) {
            IndexState state = current.index(index);
            boolean busy = state != null && state.status() == DocumentStatus.PROCESSING;
            boolean done = state != null && state.status() == DocumentStatus.READY;
            if (!busy && (state == null || !done || force)) {
                todo.add(index);
            }
        }
        DocumentRecord next = current.withFileStored(current.fileStored() || candidate.fileStored());
        for (String index : todo) {
            next = next.withIndex(index, IndexState.processing());
        }
        records.put(next.documentId(), next);
        save();
        return new Claim(next, List.copyOf(todo));
    }

    public synchronized void update(String documentId, UnaryOperator<DocumentRecord> change) {
        if (records.computeIfPresent(documentId, (id, record) -> change.apply(record)) != null) {
            save();
        }
    }

    public void updateIndex(String documentId, String index, IndexState state) {
        update(documentId, r -> r.withIndex(index, state));
    }

    /** @throws DocumentBusyException si une indexation est en cours : elle reecrirait des chunks apres la suppression */
    public synchronized Optional<DocumentRecord> remove(String documentId) {
        DocumentRecord record = records.get(documentId);
        if (record == null) {
            return Optional.empty();
        }
        if (record.status() == DocumentStatus.PROCESSING) {
            throw new DocumentBusyException(documentId);
        }
        records.remove(documentId);
        save();
        return Optional.of(record);
    }

    public Optional<DocumentRecord> find(String documentId) {
        return Optional.ofNullable(records.get(documentId));
    }

    /** Du plus recent au plus ancien. */
    public List<DocumentRecord> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(DocumentRecord::submittedAt).reversed())
                .toList();
    }

    private void load() {
        if (!Files.exists(file)) {
            LOG.info("Registre {} absent : demarrage sans document", file.toAbsolutePath());
            return;
        }
        JsonNode saved;
        try {
            saved = JSON.readTree(file.toFile());
        } catch (IOException e) {
            // On refuse de demarrer plutot que de repartir vide : la prochaine ecriture
            // effacerait le catalogue. Reparer ou supprimer le fichier a la main.
            throw new IllegalStateException("Registre illisible : " + file.toAbsolutePath() + " (" + e.getMessage() + ")", e);
        }

        boolean changed = false;
        for (JsonNode node : saved) {
            boolean legacy = !node.has("indexes");
            DocumentRecord record = JSON.convertValue(legacy ? migrate((ObjectNode) node) : node, DocumentRecord.class);
            changed |= legacy;
            // Un PROCESSING relu ici n'a plus de thread pour le finir : il resterait bloque pour
            // toujours. FAILED permet au prochain import (ou a une reconstruction) de le relancer.
            for (Map.Entry<String, IndexState> entry : record.indexes().entrySet()) {
                if (entry.getValue().status() == DocumentStatus.PROCESSING) {
                    record = record.withIndex(entry.getKey(), IndexState.failed(INTERRUPTED, Instant.now()));
                    changed = true;
                }
            }
            records.put(record.documentId(), record);
        }
        if (changed) {
            save();
        }
        LOG.info("Registre {} : {} document(s) recharge(s)", file.toAbsolutePath(), records.size());
    }

    /**
     * Registre ecrit avant les index multiples : un statut unique, dont les chunks sont dans la
     * collection de l'index historique. Le fichier d'origine n'etait pas conserve.
     */
    private ObjectNode migrate(ObjectNode node) {
        ObjectNode state = JSON.createObjectNode();
        state.set("status", node.path("status"));
        state.set("sections", node.has("sections") ? node.get("sections") : node.path("pages"));
        state.set("chunks", node.path("chunks"));
        state.set("error", node.path("error"));
        state.set("completedAt", node.path("completedAt"));
        ObjectNode migrated = node.deepCopy();
        migrated.putObject("indexes").set(legacyIndex, state);
        migrated.put("fileStored", false);
        return migrated;
    }

    /**
     * Ecriture dans un fichier temporaire puis renommage atomique : un arret brutal pendant
     * l'ecriture laisse l'ancien fichier intact, jamais un JSON tronque.
     */
    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "documents-", ".json.tmp");
            JSON.writeValue(tmp.toFile(), all());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du registre impossible : " + file.toAbsolutePath(), e);
        }
    }

    /** @param toIndex index a (re)construire maintenant pour ce document ; vide = rien a faire */
    public record Claim(DocumentRecord record, List<String> toIndex) {

        public boolean created() {
            return !toIndex.isEmpty();
        }
    }
}
