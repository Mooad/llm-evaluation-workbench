package com.aimastery.rag.store.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.chunk.SourceLocation;
import com.aimastery.rag.chunk.TimeRangeLocation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QdrantPayloadTest {

    @Test
    void une_page_survit_a_l_aller_retour_par_qdrant() {
        assertRoundTrip(new PageLocation(365));
    }

    @Test
    void une_plage_de_pages_survit_a_l_aller_retour_par_qdrant() {
        assertRoundTrip(new PageRangeLocation(222, 224));
    }

    @Test
    void un_intervalle_de_temps_survit_a_l_aller_retour_par_qdrant() {
        assertRoundTrip(new TimeRangeLocation(134_000, 168_000));
    }

    @Test
    void un_point_ecrit_avant_locationType_se_lit_comme_une_page() {
        Map<String, Object> legacy = Map.of("documentId", "doc-1", "chunkIndex", 618L, "fichier", "notice.pdf", "page", 365L);

        Chunk chunk = QdrantPayload.toChunk("id", "texte", legacy);

        assertThat(chunk.location()).isEqualTo(new PageLocation(365));
        assertThat(chunk.chunkIndex()).isEqualTo(618);
        assertThat(chunk.sourceType()).isNull();
    }

    /** Qdrant rend les entiers en Long : on simule ce retour pour tester la vraie lecture. */
    private static void assertRoundTrip(SourceLocation location) {
        Chunk chunk = new Chunk("id", "doc-1", 7, "source", "pdf", "texte", location, "Garantie");

        Map<String, Object> payload = new HashMap<>();
        QdrantPayload.toPayload(chunk).forEach((key, value) ->
                payload.put(key, value instanceof Integer i ? Long.valueOf(i) : value));

        assertThat(QdrantPayload.toChunk("id", "texte", payload)).isEqualTo(chunk);
    }
}
