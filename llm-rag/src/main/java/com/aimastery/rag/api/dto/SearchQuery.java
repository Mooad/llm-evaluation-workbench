package com.aimastery.rag.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * @param documentIds documents ou chercher ; absent ou vide = tous
 * @param topK        nombre de chunks voulus, 5 par defaut
 * @param collections groupes de documents ou chercher ; absent ou vide = tous
 * @param sections    titres de section exacts (SearchHit.section) ; absent ou vide = toutes
 * @param index       index (strategie de decoupage) ou chercher ; absent = celui par defaut
 */
public record SearchQuery(
        @NotBlank String query,
        List<String> documentIds,
        @Min(1) @Max(50) Integer topK,
        List<String> collections,
        List<String> sections,
        String index) {

    public static final int DEFAULT_TOP_K = 5;

    public int topKOrDefault() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }
}
