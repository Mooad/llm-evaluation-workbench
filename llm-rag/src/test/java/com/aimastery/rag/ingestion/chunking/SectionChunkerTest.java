package com.aimastery.rag.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SectionChunkerTest {

    private static final Encoding TOKENS = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final SectionChunker chunker = new SectionChunker(800);

    @Test
    void les_pages_consecutives_d_une_meme_section_forment_un_seul_chunk_sur_plusieurs_pages() {
        List<ChunkDraft> chunks = chunker.chunk(document(
                page(222, "Systeme de surveillance", words(40)),
                page(223, "Systeme de surveillance", words(40)),
                page(224, "Systeme de surveillance", words(40)),
                page(225, "Pressions de gonflage", words(40))));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).location()).isEqualTo(new PageRangeLocation(222, 224));
        assertThat(chunks.get(0).section()).isEqualTo("Systeme de surveillance");
        assertThat(chunks.get(1).location()).isEqualTo(new PageLocation(225));
    }

    @Test
    void une_section_trop_longue_est_coupee_en_debut_de_page_et_reste_sous_le_plafond() {
        // Chaque page fait un peu plus d'un tiers du plafond : deux tiennent ensemble, pas trois.
        String third = wordsOfTokens(300);
        List<ChunkDraft> chunks = chunker.chunk(document(
                page(10, "Entretien", third), page(11, "Entretien", third), page(12, "Entretien", third)));

        assertThat(chunks).extracting(ChunkDraft::location)
                .containsExactly(new PageRangeLocation(10, 11), new PageLocation(12));
        assertThat(chunks).allSatisfy(c -> assertThat(TOKENS.countTokens(c.text())).isLessThanOrEqualTo(800));
    }

    @Test
    void une_page_seule_plus_longue_que_le_plafond_est_redecoupee_sans_perdre_sa_page() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(5, "Tableau", words(1500))));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(ChunkDraft::location).containsOnly(new PageLocation(5));
        assertThat(chunks).allSatisfy(c -> assertThat(TOKENS.countTokens(c.text())).isLessThanOrEqualTo(800));
    }

    @Test
    void sans_sommaire_chaque_page_reste_seule() {
        List<ChunkDraft> chunks = chunker.chunk(document(page(1, null, words(50)), page(2, null, words(50))));

        assertThat(chunks).extracting(ChunkDraft::location).containsExactly(new PageLocation(1), new PageLocation(2));
    }

    @Test
    void la_description_donne_le_plafond() {
        assertThat(chunker.description()).isEqualTo("section(<=800)");
    }

    private static ExtractedDocument document(Section... pages) {
        return new ExtractedDocument(List.of(pages));
    }

    private static Section page(int number, String title, String text) {
        return new Section(text, new PageLocation(number), title);
    }

    /** Un texte d'environ n tokens, mesure avec l'encodage du chunker. */
    private static String wordsOfTokens(int tokens) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; TOKENS.countTokens(text.toString()) < tokens; i++) {
            text.append("mot").append(i).append(' ');
        }
        return text.toString().strip();
    }

    private static String words(int count) {
        return IntStream.range(0, count).mapToObj(i -> "mot" + i).collect(Collectors.joining(" "));
    }
}
