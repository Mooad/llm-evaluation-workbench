package com.aimastery.rag.evaluation;

import java.util.List;

/** Le jeu de questions d'evaluation (src/main/resources/evaluation/rag-dataset.json). */
public record EvalDataset(String name, String description, List<EvalCase> cases) {

    /**
     * @param document fichier qui contient la reponse ; null pour une question hors corpus
     * @param pages    pages du fichier ou se trouve la reponse (n'importe laquelle compte)
     * @param variants groupes de pages decrivant chacun une variante : une recherche complete
     *                 doit ramener au moins une page de chaque groupe
     * @param facts    faits que la reponse doit contenir ; "a|b" = l'une des formes
     * @param abstain  vrai si la reponse n'est dans aucun document : le systeme doit le dire
     */
    public record EvalCase(
            String id,
            String category,
            String question,
            String document,
            List<Integer> pages,
            List<List<Integer>> variants,
            List<String> facts,
            boolean abstain) {

        public List<Integer> pagesOrEmpty() {
            return pages == null ? List.of() : pages;
        }

        public List<String> factsOrEmpty() {
            return facts == null ? List.of() : facts;
        }
    }
}
