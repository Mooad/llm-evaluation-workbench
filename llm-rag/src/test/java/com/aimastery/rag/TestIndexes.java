package com.aimastery.rag;

import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.ingestion.IngestionPipeline;
import com.aimastery.rag.ingestion.chunking.SectionChunker;
import com.aimastery.rag.ingestion.chunking.TokenChunker;
import com.aimastery.rag.ingestion.source.SourceFormat;
import com.aimastery.rag.ingestion.source.SourceFormats;
import com.aimastery.rag.ingestion.source.pdf.PdfDocumentReader;
import java.util.List;

/** Les deux index de la configuration (A tokens, B sections), sur des stores et pipelines fournis par le test. */
public final class TestIndexes {

    private TestIndexes() {
    }

    public static SearchIndexes of(ChunkStore storeA, IngestionPipeline pipelineA, ChunkStore storeB, IngestionPipeline pipelineB) {
        PdfDocumentReader pdf = new PdfDocumentReader();
        return new SearchIndexes(List.of(
                new SearchIndex("A", "Tokens 400/80", "token(400/80)", "test-a",
                        new SourceFormats(List.of(new SourceFormat("pdf", pdf, new TokenChunker(400, 80)))), storeA, pipelineA),
                new SearchIndex("B", "Par section", "section(<=800)", "test-b",
                        new SourceFormats(List.of(new SourceFormat("pdf", pdf, new SectionChunker(800)))), storeB, pipelineB)));
    }

    /** Un seul index, pour les tests qui ne comparent pas de strategies. */
    public static SearchIndexes single(ChunkStore store) {
        return new SearchIndexes(List.of(new SearchIndex("A", "Tokens 400/80", "token(400/80)", "test-a",
                new SourceFormats(List.of(new SourceFormat("pdf", new PdfDocumentReader(), new TokenChunker(400, 80)))),
                store, new IngestionPipeline(store, "test-model"))));
    }
}
