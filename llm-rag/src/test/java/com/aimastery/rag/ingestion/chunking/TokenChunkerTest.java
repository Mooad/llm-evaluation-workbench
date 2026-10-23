package com.aimastery.rag.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenChunkerTest {

    private static final Encoding TOKENS = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final TokenChunker chunker = new TokenChunker(100, 20);

    @Test
    void aucun_chunk_ne_depasse_la_taille_configuree() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(1, sentences(1, 120))));

        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(TOKENS.countTokens(chunk.text())).isLessThanOrEqualTo(100));
    }

    @Test
    void chaque_chunk_reprend_la_fin_du_precedent() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(1, sentences(1, 120))));

        for (int i = 1; i < chunks.size(); i++) {
            String start = chunks.get(i).text().substring(0, 30);
            assertThat(chunks.get(i - 1).text()).as("chunk %d", i).contains(start);
        }
    }

    @Test
    void sans_chevauchement_les_chunks_ne_se_recouvrent_pas() {
        List<ChunkDraft> chunks = new TokenChunker(100, 0).chunk(document(page(1, sentences(1, 120))));

        for (int i = 1; i < chunks.size(); i++) {
            String start = chunks.get(i).text().substring(0, 30);
            assertThat(chunks.get(i - 1).text()).as("chunk %d", i).doesNotContain(start);
        }
    }

    @Test
    void le_chevauchement_ne_traverse_pas_les_sections() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(1, sentences(1, 60)), page(2, sentences(500, 60))));

        ChunkDraft firstOfPage2 = chunks.stream().filter(c -> c.location().equals(new PageLocation(2)))
                .findFirst().orElseThrow();
        assertThat(firstOfPage2.text()).startsWith("Phrase numero 500 ");
    }

    @Test
    void chaque_chunk_garde_la_localisation_de_sa_section_dans_l_ordre() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(3, sentences(1, 60)), page(4, sentences(100, 60))));

        assertThat(chunks).extracting(ChunkDraft::location).containsOnly(new PageLocation(3), new PageLocation(4));
        assertThat(chunks.get(0).location()).isEqualTo(new PageLocation(3));
        assertThat(chunks.get(chunks.size() - 1).location()).isEqualTo(new PageLocation(4));
        // Aucune page 3 apres une page 4 : l'ordre de la source est conserve.
        int firstPage4 = chunks.indexOf(chunks.stream().filter(c -> c.location().equals(new PageLocation(4))).findFirst().orElseThrow());
        assertThat(chunks.subList(firstPage4, chunks.size())).extracting(ChunkDraft::location).containsOnly(new PageLocation(4));
    }

    @Test
    void une_section_courte_donne_un_seul_chunk_intact() {
        String text = "La pression des pneus se controle a froid.";

        assertThat(chunker.chunk(document(page(1, text)))).singleElement().extracting(ChunkDraft::text).isEqualTo(text);
    }

    @Test
    void la_description_donne_les_reglages_effectifs() {
        assertThat(chunker.description()).isEqualTo("token(100/20)");
    }

    @Test
    void un_chevauchement_egal_ou_superieur_a_la_taille_est_refuse() {
        assertThatThrownBy(() -> new TokenChunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenChunker(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ExtractedDocument document(Section... sections) {
        return new ExtractedDocument(List.of(sections));
    }

    private static Section page(int number, String text) {
        return new Section(text, new PageLocation(number), "Section " + number);
    }

    /** Phrases numerotees, donc toutes differentes : un recouvrement se voit sans ambiguite. */
    private static String sentences(int from, int count) {
        return IntStream.range(from, from + count)
                .mapToObj(i -> "Phrase numero " + i + " : la pression des pneus se verifie a froid, roue de secours comprise.")
                .collect(Collectors.joining(" "));
    }
}
