package com.aimastery.rag.config;

import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.FileStore;
import com.aimastery.rag.embedding.EmbeddingClient;
import com.aimastery.rag.embedding.OllamaEmbeddingClient;
import com.aimastery.rag.evaluation.EvalDataset;
import com.aimastery.rag.evaluation.ReportStore;
import com.aimastery.rag.evaluation.RetrievalEvaluator;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.ingestion.IngestionPipeline;
import com.aimastery.rag.ingestion.IngestionService;
import com.aimastery.rag.ingestion.chunking.Chunker;
import com.aimastery.rag.ingestion.chunking.SectionChunker;
import com.aimastery.rag.ingestion.chunking.TokenChunker;
import com.aimastery.rag.ingestion.source.DocumentReader;
import com.aimastery.rag.ingestion.source.SourceFormat;
import com.aimastery.rag.ingestion.source.SourceFormats;
import com.aimastery.rag.ingestion.source.pdf.PdfDocumentReader;
import com.aimastery.rag.search.SearchService;
import com.aimastery.rag.store.qdrant.QdrantChunkStore;
import com.aimastery.rag.store.qdrant.SpringAiEmbeddingAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le branchement complet, en un seul endroit : quels index, quels formats, quelle base, quel modele.
 * Aucune auto-configuration Spring AI : on lit ici ce qui parle a quoi.
 *
 * Ajouter un format = une ligne dans formats(). Ajouter une strategie de decoupage = un index
 * dans application.yml. Changer de base = une autre implementation de ChunkStore.
 * Passer par le Gateway pour les embeddings = un autre bean EmbeddingClient.
 */
@Configuration
public class RagConfig {

    /** Les tests le remplacent par un @Primary ; le Gateway le remplacera ici. */
    @Bean
    EmbeddingClient embeddingClient(RagProperties properties) {
        return new OllamaEmbeddingClient(properties.embedding());
    }

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(RagProperties properties) {
        RagProperties.Qdrant qdrant = properties.qdrant();
        return new QdrantClient(QdrantGrpcClient.newBuilder(qdrant.host(), qdrant.port(), false).build());
    }

    /**
     * Un VectorStore par index, chacun dans sa collection. initializeSchema : la collection est
     * creee au demarrage si elle n'existe pas (distance cosine, dimension mesuree sur le modele).
     * Qdrant ou Ollama absent = l'application ne demarre pas, plutot que d'echouer au premier import.
     */
    @Bean
    SearchIndexes searchIndexes(RagProperties properties, QdrantClient qdrantClient, EmbeddingClient embeddingClient)
            throws Exception {
        SpringAiEmbeddingAdapter embeddings = new SpringAiEmbeddingAdapter(embeddingClient,
                properties.embedding().sectionInEmbedding());
        DocumentReader pdf = new PdfDocumentReader();
        List<SearchIndex> indexes = new ArrayList<>();
        for (RagProperties.IndexConfig config : properties.indexes()) {
            Chunker chunker = chunker(config);
            String collection = properties.qdrant().collection(config.collection());
            QdrantVectorStore vectorStore = QdrantVectorStore.builder(qdrantClient, embeddings)
                    .collectionName(collection)
                    .initializeSchema(true)
                    .build();
            // Hors du contexte Spring : l'initialisation (creation de la collection) se lance a la main.
            vectorStore.afterPropertiesSet();
            QdrantChunkStore store = new QdrantChunkStore(vectorStore);
            indexes.add(new SearchIndex(config.name(), config.label(), chunker.description(), collection,
                    formats(pdf, chunker), store, new IngestionPipeline(store, embeddingClient.model())));
        }
        return new SearchIndexes(indexes);
    }

    /**
     * Les formats acceptes, du plus specifique au plus general (le premier qui reconnait le
     * fichier l'emporte). A venir : Markdown, transcription Moviid (TimeRangeLocation), HTML.
     */
    private static SourceFormats formats(DocumentReader pdf, Chunker chunker) {
        return new SourceFormats(List.of(new SourceFormat("pdf", pdf, chunker)));
    }

    private static Chunker chunker(RagProperties.IndexConfig config) {
        if (config.strategy() == null) {
            throw new IllegalArgumentException("Strategie absente pour l'index " + config.name() + " (token ou section)");
        }
        return switch (config.strategy()) {
            case "token" -> new TokenChunker(config.chunkSize(), config.overlap());
            case "section" -> new SectionChunker(config.chunkSize());
            default -> throw new IllegalArgumentException(
                    "Strategie inconnue pour l'index " + config.name() + " : " + config.strategy() + " (token ou section)");
        };
    }

    /** Peu de threads : Ollama traite les embeddings un lot a la fois, en paralleliser plus ne gagne rien. */
    @Bean
    ThreadPoolTaskExecutor ingestionExecutor(RagProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.ingestion().threads());
        executor.setMaxPoolSize(properties.ingestion().threads());
        executor.setThreadNamePrefix("ingestion-");
        return executor;
    }

    /** Les documents d'un registre anterieur aux index multiples sont rattaches au premier index. */
    @Bean
    DocumentRegistry documentRegistry(RagProperties properties) {
        return new DocumentRegistry(Path.of(properties.registry().file()), properties.indexes().get(0).name());
    }

    @Bean
    FileStore fileStore(RagProperties properties) {
        return new FileStore(Path.of(properties.storage().filesDir()));
    }

    @Bean
    IngestionService ingestionService(SearchIndexes indexes, DocumentRegistry registry, FileStore files,
                                      @Qualifier("ingestionExecutor") ThreadPoolTaskExecutor executor) {
        return new IngestionService(indexes, registry, files, executor);
    }

    @Bean
    SearchService searchService(SearchIndexes indexes, DocumentRegistry registry) {
        return new SearchService(indexes, registry);
    }

    @Bean
    RetrievalEvaluator retrievalEvaluator(RagProperties properties, ObjectMapper json, SearchService search,
                                          DocumentRegistry registry, SearchIndexes indexes,
                                          EmbeddingClient embeddingClient) throws IOException {
        try (InputStream in = new ClassPathResource(properties.evaluation().dataset()).getInputStream()) {
            return new RetrievalEvaluator(json.readValue(in, EvalDataset.class), search, registry, indexes,
                    embeddingClient.model(), properties.embedding().sectionInEmbedding());
        }
    }

    @Bean
    ReportStore reportStore(RagProperties properties, ObjectMapper json) {
        return new ReportStore(Path.of(properties.evaluation().reportsDir()), json);
    }
}
