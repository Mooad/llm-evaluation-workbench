package com.aimastery.rag.evaluation;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.evaluation.EvalDataset.EvalCase;
import com.aimastery.rag.evaluation.RetrievalReport.CaseResult;
import com.aimastery.rag.evaluation.RetrievalReport.IndexInfo;
import com.aimastery.rag.evaluation.RetrievalReport.Metrics;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.search.SearchHit;
import com.aimastery.rag.search.SearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Evalue la recherche sur le jeu de questions : aucune generation, aucun LLM.
 *
 * Chaque question cherche dans tous les documents indexes, comme un utilisateur qui ne sait
 * pas ou est la reponse : retrouver le bon document parmi les autres fait partie du test.
 */
public class RetrievalEvaluator {

    public static final String OK = "OK";
    public static final String OUT_OF_CORPUS = "OUT_OF_CORPUS";
    public static final String DOCUMENT_ABSENT = "DOCUMENT_ABSENT";

    private final EvalDataset dataset;
    private final SearchService search;
    private final DocumentRegistry registry;
    private final SearchIndexes indexes;
    private final String embeddingModel;
    private final boolean sectionInEmbedding;

    public RetrievalEvaluator(EvalDataset dataset, SearchService search, DocumentRegistry registry,
                              SearchIndexes indexes, String embeddingModel, boolean sectionInEmbedding) {
        this.dataset = dataset;
        this.search = search;
        this.registry = registry;
        this.indexes = indexes;
        this.embeddingModel = embeddingModel;
        this.sectionInEmbedding = sectionInEmbedding;
    }

    public EvalDataset dataset() {
        return dataset;
    }

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_COLLECTION = "COLLECTION";

    public RetrievalReport run(int topK) {
        return run(topK, SCOPE_ALL, null);
    }

    /** @param indexName index a evaluer (strategie de decoupage) ; null = celui par defaut */
    public RetrievalReport run(int topK, String scope, String indexName) {
        SearchIndex index = indexes.get(indexName);
        // Un document absent de cet index (pas encore reconstruit) ecarte ses questions : sinon
        // l'index serait penalise pour un document qu'il ne pouvait pas connaitre.
        List<DocumentRecord> ready = registry.all().stream()
                .filter(d -> d.readyIn(index.name()))
                .toList();
        Map<String, DocumentRecord> byFile = new HashMap<>();
        ready.forEach(d -> byFile.put(d.fileName(), d));

        List<CaseResult> results = dataset.cases().stream()
                .map(c -> evaluate(index.name(), c, topK, byFile, SCOPE_COLLECTION.equals(scope)))
                .toList();

        // Les sections se lisent sur les extraits ramenes : assez pour savoir si l'index les porte.
        int sectioned = (int) results.stream().flatMap(r -> r.hits().stream()).filter(h -> h.section() != null).count();
        int chunks = ready.stream().map(d -> d.index(index.name()).chunks()).mapToInt(n -> n == null ? 0 : n).sum();
        IndexInfo info = new IndexInfo(index.name(), index.label(), embeddingModel, index.description(), ready.size(),
                chunks, sectioned, sectionInEmbedding);
        return new RetrievalReport(dataset.name(), Instant.now(), topK, scope, info, metrics(results), results);
    }

    private CaseResult evaluate(String index, EvalCase c, int topK, Map<String, DocumentRecord> byFile,
                                boolean byCollection) {
        // Document attendu non indexe : la question ne mesure rien, on l'ecarte au lieu de la compter fausse.
        if (!c.abstain() && !byFile.containsKey(c.document())) {
            return result(c, DOCUMENT_ABSENT, null, null, null, List.of());
        }
        String collection = byCollection && !c.abstain() ? byFile.get(c.document()).collection() : null;
        List<SearchHit> hits = search.search(index, c.question(),
                new SearchService.Scope(List.of(), collection == null ? List.of() : List.of(collection), List.of()), topK);
        if (c.abstain()) {
            return result(c, OUT_OF_CORPUS, null, null, null, hits);
        }

        Integer rank = null;
        for (int i = 0; i < hits.size(); i++) {
            if (onPages(hits.get(i), c.document(), c.pagesOrEmpty())) {
                rank = i + 1;
                break;
            }
        }
        Boolean covered = c.variants() == null ? null : c.variants().stream()
                .allMatch(group -> hits.stream().anyMatch(hit -> onPages(hit, c.document(), group)));
        Double precision = hits.isEmpty() ? null
                : (double) hits.stream().filter(hit -> c.document().equals(hit.fileName())).count() / hits.size();
        return result(c, OK, rank, covered, precision, hits);
    }

    /** Un extrait sur plusieurs pages (decoupage par section) compte s'il couvre une page attendue. */
    static boolean onPages(SearchHit hit, String document, List<Integer> pages) {
        if (!document.equals(hit.fileName())) {
            return false;
        }
        if (hit.location() instanceof PageLocation page) {
            return pages.contains(page.page());
        }
        return hit.location() instanceof PageRangeLocation range && pages.stream().anyMatch(range::contains);
    }

    private static CaseResult result(EvalCase c, String status, Integer rank, Boolean covered, Double precision,
                                     List<SearchHit> hits) {
        return new CaseResult(c.id(), c.category(), c.question(), c.document(), c.pagesOrEmpty(), c.factsOrEmpty(),
                c.abstain(), status, rank, covered, precision, hits);
    }

    static Metrics metrics(List<CaseResult> results) {
        List<CaseResult> answerable = results.stream().filter(r -> OK.equals(r.status())).toList();
        List<CaseResult> outOfCorpus = results.stream().filter(r -> OUT_OF_CORPUS.equals(r.status())).toList();
        List<CaseResult> withVariants = answerable.stream().filter(r -> r.variantsCovered() != null).toList();
        int skipped = (int) results.stream().filter(r -> DOCUMENT_ABSENT.equals(r.status())).count();

        return new Metrics(
                answerable.size(),
                skipped,
                outOfCorpus.size(),
                rate(answerable, r -> r.rank() != null && r.rank() == 1),
                rate(answerable, r -> r.rank() != null),
                mean(answerable.stream().map(r -> r.rank() == null ? 0.0 : 1.0 / r.rank()).toList()),
                rate(withVariants, r -> Boolean.TRUE.equals(r.variantsCovered())),
                mean(answerable.stream().map(CaseResult::documentPrecision).filter(p -> p != null).toList()),
                mean(topScores(answerable)),
                mean(topScores(outOfCorpus)));
    }

    private static List<Double> topScores(List<CaseResult> results) {
        List<Double> scores = new ArrayList<>();
        for (CaseResult r : results) {
            if (!r.hits().isEmpty()) {
                scores.add(r.hits().get(0).score());
            }
        }
        return scores;
    }

    private static Double rate(List<CaseResult> results, Predicate<CaseResult> success) {
        return results.isEmpty() ? null : (double) results.stream().filter(success).count() / results.size();
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
