package com.aimastery.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aimastery.rag.InMemoryChunkStore;
import com.aimastery.rag.TestIndexes;
import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.DocumentStatus;
import com.aimastery.rag.document.FileStore;
import com.aimastery.rag.ingestion.source.SourceFile;
import com.aimastery.rag.ingestion.source.SourceFormat;
import com.aimastery.rag.ingestion.source.UnsupportedDocumentException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestionServiceTest {

    private static final byte[] PDF = "%PDF-1.7 une notice".getBytes(StandardCharsets.US_ASCII);

    private final IngestionPipeline pipelineA = mock(IngestionPipeline.class);
    private final IngestionPipeline pipelineB = mock(IngestionPipeline.class);
    private final InMemoryChunkStore storeA = new InMemoryChunkStore();
    private final InMemoryChunkStore storeB = new InMemoryChunkStore();
    private DocumentRegistry registry;
    private FileStore files;
    private Path filesDir;
    private IngestionService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        registry = new DocumentRegistry(dir.resolve("documents.json"), "A");
        filesDir = dir.resolve("files");
        files = new FileStore(filesDir);
        // Execution immediate : le test lit le statut final sans attendre.
        service = new IngestionService(TestIndexes.of(storeA, pipelineA, storeB, pipelineB), registry, files, Runnable::run);
        when(pipelineA.ingest(anyString(), any(SourceFile.class), any(SourceFormat.class))).thenReturn(new IngestionResult(3, 12));
        when(pipelineB.ingest(anyString(), any(SourceFile.class), any(SourceFormat.class))).thenReturn(new IngestionResult(3, 7));
    }

    @Test
    void un_document_est_indexe_dans_chaque_index_et_son_fichier_conserve() {
        String id = service.submit("notice.pdf", PDF).document().documentId();

        DocumentRecord document = registry.find(id).orElseThrow();
        assertThat(document.status()).isEqualTo(DocumentStatus.READY);
        assertThat(document.index("A").chunks()).isEqualTo(12);
        assertThat(document.index("B").chunks()).isEqualTo(7);
        assertThat(files.read(id)).hasValue(PDF);
    }

    @Test
    void le_meme_fichier_reimporte_n_est_pas_indexe_deux_fois() {
        IngestionService.Submission first = service.submit("notice.pdf", PDF);
        IngestionService.Submission second = service.submit("copie-renommee.pdf", PDF.clone());

        assertThat(first.alreadyKnown()).isFalse();
        assertThat(second.alreadyKnown()).isTrue();
        assertThat(second.document().documentId()).isEqualTo(first.document().documentId());
        verify(pipelineA, times(1)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
        verify(pipelineB, times(1)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
    }

    @Test
    void seul_l_index_en_echec_est_relance_au_prochain_import() {
        when(pipelineB.ingest(anyString(), any(SourceFile.class), any(SourceFormat.class)))
                .thenThrow(new IllegalStateException("Ollama arrete"))
                .thenReturn(new IngestionResult(3, 7));

        String id = service.submit("notice.pdf", PDF).document().documentId();
        assertThat(registry.find(id).orElseThrow().index("B").error()).contains("Ollama arrete");

        service.submit("notice.pdf", PDF);

        assertThat(registry.find(id).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        verify(pipelineA, times(1)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
        verify(pipelineB, times(2)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
    }

    @Test
    void reconstruire_un_index_reindexe_les_fichiers_conserves_et_signale_les_autres() {
        service.submit("notice.pdf", PDF);
        // Document importe avant la conservation des fichiers : connu du registre, sans fichier.
        registry.claim(new DocumentRecord("aaaaaaaaaaaaaaaa", "ancien.pdf", "pdf", null, "sha-a", Map.of(), false,
                Instant.now()), List.of("A"), false);

        IngestionService.RebuildResult result = service.rebuild("B");

        assertThat(result.scheduled()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly("ancien.pdf");
        verify(pipelineB, times(2)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
        verify(pipelineA, times(1)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
        assertThat(registry.find("aaaaaaaaaaaaaaaa").orElseThrow().index("B").error()).isEqualTo(IngestionService.NO_FILE);
    }

    @Test
    void supprimer_un_document_retire_ses_chunks_de_chaque_index_son_fichier_et_son_entree() throws Exception {
        String id = service.submit("notice.pdf", PDF).document().documentId();
        storeA.replace(id, List.of(chunk(id)));
        storeB.replace(id, List.of(chunk(id)));

        service.delete(id);

        assertThat(storeA.chunks(id)).isEmpty();
        assertThat(storeB.chunks(id)).isEmpty();
        assertThat(files.read(id)).isEmpty();
        assertThat(registry.find(id)).isEmpty();
        try (var listing = Files.list(filesDir)) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void la_collection_est_normalisee_et_change_sans_reindexer() {
        service.submit("notice.pdf", PDF, false, "  Notices ");

        IngestionService.Submission moved = service.submit("notice.pdf", PDF, false, "auto");

        assertThat(moved.alreadyKnown()).isTrue();
        assertThat(moved.document().collection()).isEqualTo("auto");
        verify(pipelineA, times(1)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
    }

    @Test
    void un_import_force_reindexe_partout_avec_le_meme_identifiant() {
        String id = service.submit("notice.pdf", PDF).document().documentId();

        IngestionService.Submission forced = service.submit("notice.pdf", PDF, true, null);

        assertThat(forced.alreadyKnown()).isFalse();
        assertThat(forced.document().documentId()).isEqualTo(id);
        verify(pipelineA, times(2)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
        verify(pipelineB, times(2)).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
    }

    @Test
    void un_format_non_gere_est_refuse_avant_toute_ingestion() {
        assertThatThrownBy(() -> service.submit("faux.pdf", "<html>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> service.submit("vide.pdf", new byte[0])).isInstanceOf(InvalidDocumentException.class);
        assertThat(registry.all()).isEmpty();
        verify(pipelineA, never()).ingest(anyString(), any(SourceFile.class), any(SourceFormat.class));
    }

    private static Chunk chunk(String documentId) {
        return new Chunk(Chunk.idFor(documentId, 0), documentId, 0, "notice.pdf", "pdf", "texte", new PageLocation(1), null);
    }
}
