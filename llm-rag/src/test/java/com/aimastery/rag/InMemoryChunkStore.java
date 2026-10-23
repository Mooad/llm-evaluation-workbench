package com.aimastery.rag;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.chunk.ScoredChunk;
import com.aimastery.rag.chunk.SearchFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChunkStore de test, en memoire. Sa recherche compte les mots communs : assez pour verifier
 * le branchement, sans base vectorielle.
 */
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, List<Chunk>> byDocument = new LinkedHashMap<>();

    @Override
    public void replace(String documentId, List<Chunk> chunks) {
        byDocument.put(documentId, List.copyOf(chunks));
    }

    @Override
    public void delete(String documentId) {
        byDocument.remove(documentId);
    }

    @Override
    public List<ScoredChunk> search(String query, SearchFilter filter, int topK) {
        List<String> documentIds = filter.documentIds();
        List<String> words = List.of(query.toLowerCase().split("\\W+"));
        List<ScoredChunk> hits = new ArrayList<>();
        byDocument.forEach((documentId, chunks) -> {
            if (documentIds == null || documentIds.isEmpty() || documentIds.contains(documentId)) {
                for (Chunk chunk : chunks) {
                    if (!filter.sections().isEmpty() && !filter.sections().contains(chunk.section())) {
                        continue;
                    }
                    long common = words.stream().filter(chunk.text().toLowerCase()::contains).count();
                    hits.add(new ScoredChunk(chunk, (double) common / words.size()));
                }
            }
        });
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    public List<Chunk> chunks(String documentId) {
        return byDocument.getOrDefault(documentId, List.of());
    }
}
