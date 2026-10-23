package com.aimastery.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.InMemoryChunkStore;
import com.aimastery.rag.TestIndexes;
import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.IndexState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchServiceTest {

    private final InMemoryChunkStore store = new InMemoryChunkStore();
    private SearchService search;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        DocumentRegistry registry = new DocumentRegistry(dir.resolve("documents.json"), "A");
        index(registry, "moviid-offres", "moviid", "Reinitialisation", "support incident critique sous 1 heure");
        index(registry, "nimbus-sla", "contrats", "Support", "support incident critique sous 30 minutes");
        search = new SearchService(TestIndexes.single(store), registry);
    }

    @Test
    void une_collection_restreint_la_recherche_a_ses_documents() {
        List<SearchHit> hits = search.search(null, "support incident critique",
                new SearchService.Scope(List.of(), List.of("moviid"), List.of()), 5);

        assertThat(hits).extracting(SearchHit::documentId).containsOnly("moviid-offres");
    }

    @Test
    void une_collection_sans_document_ne_renvoie_rien_au_lieu_de_chercher_partout() {
        assertThat(search.search(null, "support", new SearchService.Scope(List.of(), List.of("inconnue"), List.of()), 5)).isEmpty();
    }

    @Test
    void une_section_restreint_la_recherche_aux_chunks_de_cette_section() {
        List<SearchHit> hits = search.search(null, "support incident critique",
                new SearchService.Scope(List.of(), List.of(), List.of("Support")), 5);

        assertThat(hits).extracting(SearchHit::section).containsOnly("Support");
    }

    @Test
    void sans_restriction_tous_les_documents_sont_interroges() {
        assertThat(search.search("support incident critique", List.of(), 5))
                .extracting(SearchHit::documentId).containsExactlyInAnyOrder("moviid-offres", "nimbus-sla");
    }

    private void index(DocumentRegistry registry, String documentId, String collection, String section, String text) {
        registry.claim(new DocumentRecord(documentId, documentId + ".pdf", "pdf", collection, "sha-" + documentId,
                Map.of(), true, Instant.now()), List.of("A"), false);
        registry.updateIndex(documentId, "A", IndexState.ready(1, 1, Instant.now()));
        store.replace(documentId, List.of(new Chunk(Chunk.idFor(documentId, 0), documentId, 0, documentId + ".pdf", "pdf",
                text, new PageLocation(1), section)));
    }
}
