package com.aimastery.rag.ingestion;

import com.aimastery.rag.document.DocumentNotFoundException;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.DocumentStatus;
import com.aimastery.rag.document.FileStore;
import com.aimastery.rag.document.IndexState;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.document.DocumentBusyException;
import com.aimastery.rag.ingestion.source.SourceFile;
import com.aimastery.rag.ingestion.source.SourceFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recoit un fichier, decide quels index le traiter, et lance l'indexation en arriere-plan.
 * L'appelant repart tout de suite avec un documentId ; il suit le statut ensuite.
 *
 * Un document est indexe dans tous les index configures, l'un apres l'autre : les comparer
 * n'a de sens que s'ils contiennent les memes documents.
 */
public class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);
    static final String NO_FILE = "fichier d'origine non conserve (importe avant la conservation) : le reimporter";

    private final SearchIndexes indexes;
    private final DocumentRegistry registry;
    private final FileStore files;
    private final Executor executor;

    public IngestionService(SearchIndexes indexes, DocumentRegistry registry, FileStore files, Executor executor) {
        this.indexes = indexes;
        this.registry = registry;
        this.files = files;
        this.executor = executor;
    }

    public Submission submit(String fileName, byte[] content) {
        return submit(fileName, content, false, null);
    }

    /**
     * @param force      reindexer meme si le document est deja pret (meme documentId, chunks remplaces)
     * @param collection groupe du document ; null = garder celui qu'il a deja
     */
    public Submission submit(String fileName, byte[] content, boolean force, String collection) {
        if (content == null || content.length == 0) {
            throw new InvalidDocumentException("Fichier vide");
        }
        SourceFile file = new SourceFile(fileName, content);
        // Le format est choisi ici, en synchrone : un fichier non gere est refuse tout de suite
        // (415), au lieu d'apparaitre plus tard comme une indexation FAILED.
        SourceFormat format = indexes.defaultIndex().formats().resolve(file);

        String sha256 = Fingerprint.sha256(content);
        String documentId = Fingerprint.documentId(sha256);
        files.save(documentId, content);

        String group = normalize(collection);
        DocumentRegistry.Claim claim = registry.claim(
                new DocumentRecord(documentId, fileName, format.type(), group, sha256, Map.of(), true, Instant.now()),
                indexes.names(), force);
        // La collection vit dans le registre, pas dans Qdrant : la changer ne demande aucun recalcul.
        if (group != null && !group.equals(claim.record().collection())) {
            registry.update(documentId, r -> r.withCollection(group));
        }
        if (claim.created()) {
            executor.execute(() -> run(documentId, file, claim.toIndex()));
        } else {
            LOG.info("documentId={} deja indexe ou en cours dans tous les index : rien a relancer", documentId);
        }
        return new Submission(registry.find(documentId).orElse(claim.record()), !claim.created());
    }

    /**
     * Reindexe tous les documents dans un index, a partir des fichiers conserves : apres un
     * changement de strategie de decoupage, ou pour remplir un index ajoute a la configuration.
     */
    public RebuildResult rebuild(String indexName) {
        SearchIndex index = indexes.get(indexName);
        int scheduled = 0;
        List<String> skipped = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        for (DocumentRecord document : registry.all()) {
            var content = files.read(document.documentId());
            if (content.isEmpty()) {
                skipped.add(document.fileName());
                registry.updateIndex(document.documentId(), index.name(), IndexState.failed(NO_FILE, Instant.now()));
                continue;
            }
            DocumentRegistry.Claim claim = registry.claim(document, List.of(index.name()), true);
            if (!claim.created()) {
                busy.add(document.fileName());
                continue;
            }
            SourceFile file = new SourceFile(document.fileName(), content.get());
            executor.execute(() -> run(document.documentId(), file, claim.toIndex()));
            scheduled++;
        }
        return new RebuildResult(index.name(), scheduled, skipped, busy);
    }

    /** Retire le document de tous les index, son fichier, puis son entree du registre. */
    public void delete(String documentId) {
        DocumentRecord document = registry.find(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.status() == DocumentStatus.PROCESSING) {
            throw new DocumentBusyException(documentId);
        }
        // Chunks d'abord : si Qdrant echoue, le document reste au registre et la suppression peut etre rejouee.
        for (SearchIndex index : indexes.all()) {
            index.store().delete(documentId);
        }
        files.delete(documentId);
        registry.remove(documentId);
        LOG.info("documentId={} supprime ({})", documentId, document.fileName());
    }

    private void run(String documentId, SourceFile file, List<String> indexNames) {
        for (String name : indexNames) {
            SearchIndex index = indexes.get(name);
            try {
                IngestionResult result = index.pipeline().ingest(documentId, file, index.formats().resolve(file));
                registry.updateIndex(documentId, name, IndexState.ready(result.sections(), result.chunks(), Instant.now()));
            } catch (RuntimeException e) {
                LOG.warn("documentId={} index={} indexation en echec", documentId, name, e);
                registry.updateIndex(documentId, name, IndexState.failed(describe(e), Instant.now()));
            }
        }
    }

    /** Minuscules et sans espaces autour : "Moviid " et "moviid" sont la meme collection. */
    private static String normalize(String collection) {
        if (collection == null || collection.isBlank()) {
            return null;
        }
        return collection.strip().toLowerCase(Locale.ROOT);
    }

    /** Le message de la cause racine : c'est lui qui dit quoi reparer (Ollama arrete, PDF corrompu...). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == e ? message : e.getMessage() + " (cause : " + message + ")";
    }

    /** @param alreadyKnown vrai si ce contenu etait deja indexe ou en cours partout : rien n'a ete relance */
    public record Submission(DocumentRecord document, boolean alreadyKnown) {
    }

    /**
     * @param scheduled documents dont la reindexation est lancee
     * @param skipped   documents sans fichier conserve : a reimporter
     * @param busy      documents deja en cours d'indexation dans cet index
     */
    public record RebuildResult(String index, int scheduled, List<String> skipped, List<String> busy) {
    }
}
