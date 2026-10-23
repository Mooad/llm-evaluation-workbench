package com.aimastery.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.rag.InMemoryChunkStore;
import com.aimastery.rag.TestIndexes;
import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.IndexState;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.evaluation.EvalDataset.EvalCase;
import com.aimastery.rag.search.SearchHit;
import com.aimastery.rag.search.SearchService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalEvaluatorTest {

    private final InMemoryChunkStore store = new InMemoryChunkStore();
    private DocumentRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        registry = new DocumentRegistry(dir.resolve("documents.json"), "A");
        registry.claim(new DocumentRecord("kelvio", "kelvio.pdf", "pdf", "notices", "sha", Map.of(), true, Instant.now()),
                List.of("A"), false);
        registry.updateIndex("kelvio", "A", IndexState.ready(3, 3, Instant.now()));
        store.replace("kelvio", List.of(
                chunk(0, 2, "installation murale a 1,50 metre"),
                chunk(1, 4, "reinitialisation wifi bouton mode 10 secondes"),
                chunk(2, 5, "reinitialisation zigbee bouton plus 5 fois")));
    }

    @Test
    void mesure_le_rang_de_la_bonne_page_et_la_couverture_des_variantes() {
        RetrievalReport report = evaluator(
                answerable("wifi", "reinitialisation wifi bouton", List.of(4), null),
                answerable("deux_variantes", "reinitialisation bouton", List.of(4, 5), List.of(List.of(4), List.of(5))),
                answerable("rate", "garantie trois ans", List.of(7), null)).run(1);

        assertThat(report.cases()).extracting(RetrievalReport.CaseResult::rank).containsExactly(1, 1, null);
        // Avec un seul extrait, une seule des deux variantes peut etre ramenee.
        assertThat(report.cases().get(1).variantsCovered()).isFalse();
        assertThat(report.metrics().recallAtK()).isEqualTo(2.0 / 3);
        assertThat(report.metrics().variantCoverage()).isZero();
    }

    @Test
    void une_question_dont_le_document_n_est_pas_indexe_est_ecartee_et_non_comptee_fausse() {
        EvalCase absent = new EvalCase("absent", "chiffre", "question", "absent.pdf", List.of(1), null, List.of(), false);

        RetrievalReport report = evaluator(absent, answerable("wifi", "reinitialisation wifi", List.of(4), null)).run(5);

        assertThat(report.cases().get(0).status()).isEqualTo(RetrievalEvaluator.DOCUMENT_ABSENT);
        assertThat(report.metrics().skipped()).isEqualTo(1);
        assertThat(report.metrics().evaluated()).isEqualTo(1);
        assertThat(report.metrics().recallAtK()).isEqualTo(1.0);
    }

    @Test
    void une_question_hors_corpus_garde_son_meilleur_score_sans_compter_dans_le_rappel() {
        EvalCase outside = new EvalCase("hors", "hors_corpus", "capitale de l australie", null, null, null, null, true);

        RetrievalReport report = evaluator(outside).run(5);

        assertThat(report.cases().get(0).status()).isEqualTo(RetrievalEvaluator.OUT_OF_CORPUS);
        assertThat(report.metrics().outOfCorpus()).isEqualTo(1);
        assertThat(report.metrics().recallAtK()).isNull();
        assertThat(report.metrics().meanTopScoreOutOfCorpus()).isNotNull();
    }

    @Test
    void un_extrait_sur_plusieurs_pages_compte_s_il_couvre_la_page_attendue() {
        SearchHit range = new SearchHit("kelvio", "kelvio.pdf", "pdf", 0, new PageRangeLocation(3, 5), null, "", 0.7, "", "");

        assertThat(RetrievalEvaluator.onPages(range, "kelvio.pdf", List.of(4))).isTrue();
        assertThat(RetrievalEvaluator.onPages(range, "kelvio.pdf", List.of(6))).isFalse();
        assertThat(RetrievalEvaluator.onPages(range, "autre.pdf", List.of(4))).isFalse();
    }

    @Test
    void le_rapport_dit_quel_index_a_ete_evalue() {
        RetrievalReport report = evaluator(answerable("wifi", "reinitialisation wifi", List.of(4), null)).run(5);

        assertThat(report.index().name()).isEqualTo("A");
        assertThat(report.index().chunker()).isEqualTo("token(400/80)");
        assertThat(report.index().chunks()).isEqualTo(3);
    }

    private RetrievalEvaluator evaluator(EvalCase... cases) {
        SearchIndexes indexes = TestIndexes.single(store);
        return new RetrievalEvaluator(new EvalDataset("test", "", List.of(cases)),
                new SearchService(indexes, registry), registry, indexes, "test-model", false);
    }

    private static EvalCase answerable(String id, String question, List<Integer> pages, List<List<Integer>> variants) {
        return new EvalCase(id, "chiffre", question, "kelvio.pdf", pages, variants, List.of(), false);
    }

    private static Chunk chunk(int index, int page, String text) {
        return new Chunk(Chunk.idFor("kelvio", index), "kelvio", index, "kelvio.pdf", "pdf", text, new PageLocation(page), null);
    }
}
