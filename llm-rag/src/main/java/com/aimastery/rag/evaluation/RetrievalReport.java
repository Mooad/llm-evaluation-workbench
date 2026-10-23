package com.aimastery.rag.evaluation;

import com.aimastery.rag.search.SearchHit;
import java.time.Instant;
import java.util.List;

/**
 * Mesure de la recherche seule, sans LLM : la bonne page arrive-t-elle dans les extraits ?
 *
 * @param index comment l'index interroge a ete produit : un rapport ne se compare qu'a reglages connus
 * @param scope ALL = chaque question cherche dans tous les documents ; COLLECTION = seulement dans la
 *              collection du document attendu, comme un utilisateur qui a choisi la bonne collection
 */
public record RetrievalReport(
        String dataset,
        Instant runAt,
        int topK,
        String scope,
        IndexInfo index,
        Metrics metrics,
        List<CaseResult> cases) {

    /**
     * @param name               index evalue ("A", "B"...) ; label = son nom lisible
     * @param sectionedChunks    extraits ramenes qui portent un titre de section : 0 = index sans sections
     * @param sectionInEmbedding reglage de l'instance : le titre est-il vectorise avec le texte ?
     */
    public record IndexInfo(String name, String label, String embeddingModel, String chunker, int documents, int chunks,
                            Integer sectionedChunks, boolean sectionInEmbedding) {
    }

    /**
     * Toutes les valeurs sont des taux de 0 a 1, null quand aucun cas ne permet de les calculer.
     *
     * @param evaluated          questions dont la reponse est dans un document indexe
     * @param skipped            questions dont le document attendu n'est pas indexe : ecartees
     * @param recallAt1          bonne page en premiere position
     * @param recallAtK          bonne page parmi les K extraits envoyes au LLM
     * @param mrr                moyenne de 1/rang de la bonne page (0 si absente) : 1 = toujours premiere
     * @param variantCoverage    questions a variantes dont chaque variante est dans les K extraits
     * @param documentPrecision  part des K extraits qui viennent du bon document (le reste est du bruit)
     * @param meanTopScoreAnswerable score du premier extrait, questions avec reponse
     * @param meanTopScoreOutOfCorpus score du premier extrait, questions hors corpus : s'il est proche
     *                               du precedent, aucun seuil de score ne peut detecter le hors-sujet
     */
    public record Metrics(
            int evaluated,
            int skipped,
            int outOfCorpus,
            Double recallAt1,
            Double recallAtK,
            Double mrr,
            Double variantCoverage,
            Double documentPrecision,
            Double meanTopScoreAnswerable,
            Double meanTopScoreOutOfCorpus) {
    }

    /**
     * @param status OK, OUT_OF_CORPUS, ou DOCUMENT_ABSENT (document attendu non indexe)
     * @param rank   position (1 = premier) du premier extrait sur une page attendue ; null = absent
     */
    public record CaseResult(
            String id,
            String category,
            String question,
            String document,
            List<Integer> pages,
            List<String> facts,
            boolean abstain,
            String status,
            Integer rank,
            Boolean variantsCovered,
            Double documentPrecision,
            List<SearchHit> hits) {
    }
}
