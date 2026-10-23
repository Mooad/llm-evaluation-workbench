package com.aimastery.rag.search;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.chunk.ScoredChunk;
import com.aimastery.rag.chunk.SearchFilter;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.index.SearchIndexes;
import java.util.List;
import java.util.Set;

/** Retrouve les chunks les plus proches d'une question. Ne genere aucune reponse. */
public class SearchService {

    private static final int EXCERPT_LENGTH = 300;

    private final SearchIndexes indexes;
    private final DocumentRegistry registry;

    public SearchService(SearchIndexes indexes, DocumentRegistry registry) {
        this.indexes = indexes;
        this.registry = registry;
    }

    /** Dans l'index par defaut. @param documentIds vide ou null = tous les documents indexes */
    public List<SearchHit> search(String query, List<String> documentIds, int topK) {
        return search(null, query, new Scope(documentIds, List.of(), List.of()), topK);
    }

    /** @param index nom de l'index (strategie de decoupage) ; null = celui par defaut */
    public List<SearchHit> search(String index, String query, Scope scope, int topK) {
        ChunkStore store = indexes.get(index).store();
        List<String> documentIds = scope.documentIdsOrEmpty();
        if (!scope.collectionsOrEmpty().isEmpty()) {
            // Une collection est un groupe de documents du registre : on la traduit en documentIds,
            // ce qui permet de la changer sans reindexer. Le filtre reste applique par Qdrant.
            Set<String> inCollections = Set.copyOf(registry.all().stream()
                    .filter(d -> d.collection() != null && scope.collectionsOrEmpty().contains(d.collection()))
                    .map(DocumentRecord::documentId)
                    .toList());
            documentIds = documentIds.isEmpty()
                    ? List.copyOf(inCollections)
                    : documentIds.stream().filter(inCollections::contains).toList();
            if (documentIds.isEmpty()) {
                // Collection vide : chercher partout reviendrait a ignorer le filtre demande.
                return List.of();
            }
        }
        return store.search(query, new SearchFilter(documentIds, scope.sectionsOrEmpty()), topK).stream()
                .map(SearchService::toHit)
                .toList();
    }

    /**
     * Ou chercher. Les trois restrictions se cumulent ; une liste vide ne restreint rien.
     *
     * @param collections groupes de documents (champ collection du registre)
     * @param sections    titres de section exacts, tels que renvoyes dans SearchHit.section
     */
    public record Scope(List<String> documentIds, List<String> collections, List<String> sections) {

        List<String> documentIdsOrEmpty() {
            return documentIds == null ? List.of() : documentIds;
        }

        List<String> collectionsOrEmpty() {
            return collections == null ? List.of() : collections;
        }

        List<String> sectionsOrEmpty() {
            return sections == null ? List.of() : sections;
        }
    }

    private static SearchHit toHit(ScoredChunk scored) {
        Chunk chunk = scored.chunk();
        return new SearchHit(
                chunk.documentId(),
                chunk.fileName(),
                chunk.sourceType(),
                chunk.chunkIndex(),
                chunk.location(),
                chunk.section(),
                chunk.fileName() + ", " + chunk.location().label(),
                scored.score(),
                excerpt(chunk.text()),
                chunk.text());
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "...";
    }
}
