package com.aimastery.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.InMemoryChunkStore;
import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.TimeRangeLocation;
import com.aimastery.rag.ingestion.chunking.ChunkDraft;
import com.aimastery.rag.ingestion.chunking.Chunker;
import com.aimastery.rag.ingestion.source.DocumentReader;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.aimastery.rag.ingestion.source.SourceFile;
import com.aimastery.rag.ingestion.source.SourceFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IngestionPipelineTest {

    private final InMemoryChunkStore store = new InMemoryChunkStore();
    private final IngestionPipeline pipeline = new IngestionPipeline(store, "test-model");

    /**
     * Un format qui n'existe pas en production : une transcription "debutMs|finMs|texte" par ligne,
     * regroupee par deux segments. Le pipeline l'ingere sans rien savoir des PDF.
     */
    private final SourceFormat transcript = new SourceFormat("transcript", new TranscriptReader(), new PairChunker());

    @Test
    void un_nouveau_format_s_ingere_sans_toucher_au_pipeline() {
        SourceFile file = transcript("0|4000|Bonjour et bienvenue", "4000|9000|voici nos tarifs",
                "9000|15000|et les avantages du produit");

        IngestionResult result = pipeline.ingest("video-1", file, transcript);

        assertThat(result).isEqualTo(new IngestionResult(3, 2));
        List<Chunk> chunks = store.chunks("video-1");
        assertThat(chunks).extracting(Chunk::text)
                .containsExactly("Bonjour et bienvenue voici nos tarifs", "et les avantages du produit");
        assertThat(chunks.get(0).location()).isEqualTo(new TimeRangeLocation(0, 9000));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo("video-1");
            assertThat(chunk.sourceType()).isEqualTo("transcript");
            assertThat(chunk.fileName()).isEqualTo("video.txt");
        });
    }

    @Test
    void le_pipeline_numerote_les_chunks_de_facon_continue_et_stable() {
        SourceFile file = transcript(IntStream.range(0, 10)
                .mapToObj(i -> (i * 1000) + "|" + (i * 1000 + 1000) + "|segment " + i).toArray(String[]::new));

        pipeline.ingest("video-1", file, transcript);
        List<Chunk> first = store.chunks("video-1");
        pipeline.ingest("video-1", file, transcript);

        assertThat(first).extracting(Chunk::chunkIndex).containsExactly(0, 1, 2, 3, 4);
        assertThat(store.chunks("video-1")).extracting(Chunk::id)
                .containsExactlyElementsOf(first.stream().map(Chunk::id).toList())
                .doesNotHaveDuplicates();
        assertThat(Chunk.idFor("video-2", 0)).isNotEqualTo(first.get(0).id());
    }

    private static SourceFile transcript(String... lines) {
        return new SourceFile("video.txt", ("TRANSCRIPT\n" + String.join("\n", lines)).getBytes(StandardCharsets.UTF_8));
    }

    private static final class TranscriptReader implements DocumentReader {

        @Override
        public boolean supports(SourceFile file) {
            return file.startsWith("TRANSCRIPT\n");
        }

        @Override
        public ExtractedDocument read(SourceFile file) {
            String[] lines = new String(file.content(), StandardCharsets.UTF_8).split("\n");
            List<Section> sections = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split("\\|", 3);
                sections.add(new Section(parts[2],
                        new TimeRangeLocation(Long.parseLong(parts[0]), Long.parseLong(parts[1])), null));
            }
            return new ExtractedDocument(sections);
        }
    }

    /** Fusionne les segments deux par deux : c'est ce qu'un chunker par fenetre de temps fera. */
    private static final class PairChunker implements Chunker {

        @Override
        public List<ChunkDraft> chunk(ExtractedDocument document) {
            List<Section> sections = document.sections();
            List<ChunkDraft> chunks = new ArrayList<>();
            for (int i = 0; i < sections.size(); i += 2) {
                Section first = sections.get(i);
                Section last = sections.get(Math.min(i + 1, sections.size() - 1));
                String text = first == last ? first.text() : first.text() + " " + last.text();
                chunks.add(new ChunkDraft(text, new TimeRangeLocation(
                        ((TimeRangeLocation) first.location()).startMs(), ((TimeRangeLocation) last.location()).endMs()), null));
            }
            return chunks;
        }

        @Override
        public String description() {
            return "pair";
        }
    }
}
