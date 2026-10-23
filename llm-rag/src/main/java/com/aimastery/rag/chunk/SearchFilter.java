package com.aimastery.rag.chunk;

import java.util.List;

/**
 * Restriction de la recherche, appliquee par la base vectorielle avant le classement : un chunk
 * exclu ici n'arrive jamais au LLM. Deterministe, contrairement a une consigne de prompt.
 *
 * @param documentIds vide = tous les documents
 * @param sections    titres de section exacts ; vide = toutes les sections
 */
public record SearchFilter(List<String> documentIds, List<String> sections) {

    public static SearchFilter none() {
        return new SearchFilter(List.of(), List.of());
    }

    public static SearchFilter documents(List<String> documentIds) {
        return new SearchFilter(documentIds == null ? List.of() : documentIds, List.of());
    }
}
