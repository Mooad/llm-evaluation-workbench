package com.aimastery.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aimastery.rag.embedding.EmbeddingClient;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.ingestion.source.SourceFile;
import com.aimastery.rag.ingestion.source.SourceFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Ingestion du vrai PDF de data/ contre un vrai Qdrant (Testcontainers), de l'upload a la recherche.
 * Embeddings factices (HashingEmbeddingClient) : ce test verifie le pipeline, pas la qualite de bge-m3.
 * Ignore si Docker est absent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PdfIngestionContainerTest {

    private static final Path NOTICE = Path.of("data/notice-sandero-jogger-fr.pdf");
    private static final String PREFIX = "test-";
    private static final String COLLECTION_A = PREFIX + "llm-rag-chunks";
    private static final String COLLECTION_B = PREFIX + "llm-rag-chunks-section";

    @BeforeAll
    static void requireLocalNotice() {
        assumeTrue(Files.isRegularFile(NOTICE), "Local PDF fixture is not available");
    }

    @Container
    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.19.2");

    @DynamicPropertySource
    static void qdrant(DynamicPropertyRegistry registry) throws IOException {
        registry.add("rag.qdrant.host", QDRANT::getHost);
        registry.add("rag.qdrant.port", QDRANT::getGrpcPort);
        registry.add("rag.qdrant.collection-prefix", () -> PREFIX);
        // Jamais le state/ du projet : le test le remplirait de documents de test.
        Path state = Files.createTempDirectory("llm-rag-test");
        registry.add("rag.registry.file", () -> state.resolve("documents.json").toString());
        registry.add("rag.storage.files-dir", () -> state.resolve("files").toString());
    }

    @TestConfiguration
    static class FakeEmbeddings {
        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private QdrantClient qdrant;

    @Autowired
    private SearchIndexes indexes;

    @Test
    void ingere_la_notice_puis_retrouve_les_pages_sur_la_pression_des_pneus() throws Exception {
        byte[] pdf = Files.readAllBytes(NOTICE);
        MockMultipartFile file = new MockMultipartFile("file", NOTICE.getFileName().toString(),
                MediaType.APPLICATION_PDF_VALUE, pdf);
        // Les collections sont partagees avec les autres tests de la classe, dans un ordre quelconque.
        long beforeA = pointCount(COLLECTION_A);
        long beforeB = pointCount(COLLECTION_B);

        JsonNode submitted = body(mvc.perform(multipart("/documents").file(file)).andExpect(status().isAccepted()));
        String documentId = submitted.get("documentId").asText();
        assertThat(submitted.get("status").asText()).isEqualTo("PROCESSING");

        JsonNode document = awaitTerminalStatus(documentId);
        assertThat(document.get("status").asText()).as(document.toString()).isEqualTo("READY");
        assertThat(document.get("sourceType").asText()).isEqualTo("pdf");
        JsonNode a = document.get("indexes").get("A");
        JsonNode b = document.get("indexes").get("B");
        assertThat(a.get("sections").asInt()).isGreaterThan(10);
        assertThat(pointCount(COLLECTION_A) - beforeA).isEqualTo(a.get("chunks").asInt());
        assertThat(pointCount(COLLECTION_B) - beforeB).isEqualTo(b.get("chunks").asInt());
        // Par section : des chunks plus gros, donc moins nombreux que des fenetres de 400 tokens.
        assertThat(b.get("chunks").asInt()).isLessThan(a.get("chunks").asInt());

        // Reimport : meme id, rien de relance.
        JsonNode again = body(mvc.perform(multipart("/documents").file(file)).andExpect(status().isOk()));
        assertThat(again.get("documentId").asText()).isEqualTo(documentId);
        assertThat(again.get("alreadyKnown").asBoolean()).isTrue();

        JsonNode search = body(mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Quelle est la pression des pneus ?\",\"documentIds\":[\"" + documentId + "\"]}"))
                .andExpect(status().isOk()));
        JsonNode results = search.get("results");
        assertThat(results).hasSize(5);
        assertThat(results).allSatisfy(hit -> {
            assertThat(hit.get("documentId").asText()).isEqualTo(documentId);
            assertThat(hit.get("sourceType").asText()).isEqualTo("pdf");
            assertThat(hit.get("location").get("type").asText()).isEqualTo("page");
            assertThat(hit.get("location").get("page").asInt()).isPositive();
            assertThat(hit.get("citation").asText()).startsWith("notice-sandero-jogger-fr.pdf, page ");
            assertThat(hit.has("section")).isTrue();
            assertThat(hit.get("score").asDouble()).isPositive();
        });
        assertThat(results.get(0).get("content").asText().toLowerCase()).contains("pression");

        JsonNode inB = body(mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Quelle est la pression des pneus ?\",\"index\":\"B\"}"))
                .andExpect(status().isOk()));
        assertThat(inB.get("results")).isNotEmpty();
        assertThat(inB.get("results").get(0).get("content").asText().toLowerCase()).contains("pression");

        // Suppression : plus aucun chunk dans aucun index, et le document disparait du registre.
        mvc.perform(delete("/documents/" + documentId)).andExpect(status().isNoContent());
        assertThat(pointCount(COLLECTION_A)).isEqualTo(beforeA);
        assertThat(pointCount(COLLECTION_B)).isEqualTo(beforeB);
        mvc.perform(get("/documents/" + documentId)).andExpect(status().isNotFound());
    }

    @Test
    void un_index_inconnu_est_refuse_en_400() throws Exception {
        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\",\"index\":\"Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_format_non_gere_est_refuse_en_415() throws Exception {
        MockMultipartFile html = new MockMultipartFile("file", "page.pdf", MediaType.APPLICATION_PDF_VALUE,
                "<html>pas un pdf</html>".getBytes());

        JsonNode error = body(mvc.perform(multipart("/documents").file(html)).andExpect(status().isUnsupportedMediaType()));

        assertThat(error.get("error").asText()).isEqualTo("unsupported_document");
    }

    @Test
    void reindexer_le_meme_document_ne_duplique_aucun_chunk() throws Exception {
        SourceFile pdf = new SourceFile("notice.pdf", Files.readAllBytes(NOTICE));
        SearchIndex index = indexes.defaultIndex();
        SourceFormat format = index.formats().resolve(pdf);
        long before = pointCount(COLLECTION_A);

        int first = index.pipeline().ingest("reindex-test", pdf, format).chunks();
        int second = index.pipeline().ingest("reindex-test", pdf, format).chunks();

        assertThat(second).isEqualTo(first);
        assertThat(pointCount(COLLECTION_A) - before).isEqualTo(first);
    }

    @Test
    void la_recherche_sur_un_document_inconnu_ne_renvoie_rien() throws Exception {
        JsonNode search = body(mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"pression des pneus\",\"documentIds\":[\"inconnu\"]}"))
                .andExpect(status().isOk()));

        assertThat(search.get("results")).isEmpty();
    }

    private JsonNode awaitTerminalStatus(String documentId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (true) {
            JsonNode document = body(mvc.perform(get("/documents/" + documentId)).andExpect(status().isOk()));
            if (!document.get("status").asText().equals("PROCESSING") || Instant.now().isAfter(deadline)) {
                return document;
            }
            Thread.sleep(200);
        }
    }

    private long pointCount(String collection) throws Exception {
        return qdrant.countAsync(collection).get();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
