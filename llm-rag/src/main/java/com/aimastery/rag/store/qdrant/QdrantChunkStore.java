package com.aimastery.rag.store.qdrant;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.chunk.ScoredChunk;
import com.aimastery.rag.chunk.SearchFilter;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * ChunkStore sur Qdrant, via le VectorStore de Spring AI.
 *
 * Le VectorStore calcule lui-meme les embeddings (via SpringAiEmbeddingAdapter, donc notre
 * EmbeddingClient), a l'ecriture comme a la recherche. La collection (distance cosine) est
 * creee au demarrage. Les Document de Spring AI ne sortent pas de cette classe.
 */
public class QdrantChunkStore implements ChunkStore {

    private final VectorStore vectorStore;

    public QdrantChunkStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Ecrase puis nettoie, dans cet ordre : les identifiants sont stables, donc l'ecriture
     * remplace les anciens chunks un par un, puis on supprime ceux qui depassent le nouveau
     * nombre (decoupage different). Le document reste cherchable pendant toute la reindexation.
     */
    @Override
    public void replace(String documentId, List<Chunk> chunks) {
        if (!chunks.isEmpty()) {
            vectorStore.add(chunks.stream().map(QdrantChunkStore::toDocument).toList());
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(
                b.eq(QdrantPayload.DOCUMENT_ID, documentId),
                b.gte(QdrantPayload.CHUNK_INDEX, chunks.size())).build());
    }

    @Override
    public void delete(String documentId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(QdrantPayload.DOCUMENT_ID, documentId).build());
    }

    @Override
    public List<ScoredChunk> search(String query, SearchFilter filter, int topK) {
        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(topK);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op condition = null;
        if (!filter.documentIds().isEmpty()) {
            condition = b.in(QdrantPayload.DOCUMENT_ID, filter.documentIds().toArray());
        }
        if (!filter.sections().isEmpty()) {
            FilterExpressionBuilder.Op sections = b.in(QdrantPayload.SECTION, filter.sections().toArray());
            condition = condition == null ? sections : b.and(condition, sections);
        }
        if (condition != null) {
            request.filterExpression(condition.build());
        }
        return vectorStore.similaritySearch(request.build()).stream()
                .map(document -> new ScoredChunk(
                        QdrantPayload.toChunk(document.getId(), textOf(document), document.getMetadata()),
                        document.getScore() == null ? 0 : document.getScore()))
                .toList();
    }

    private static Document toDocument(Chunk chunk) {
        return Document.builder()
                .id(chunk.id())
                .text(chunk.text())
                .metadata(QdrantPayload.toPayload(chunk))
                .build();
    }

    private static String textOf(Document document) {
        return document.getText() == null ? "" : document.getText();
    }
}
